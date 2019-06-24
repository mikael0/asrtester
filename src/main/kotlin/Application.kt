import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.ktor.client.*
import io.ktor.client.engine.apache.*
import io.ktor.client.features.ClientRequestException
import io.ktor.client.features.json.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.streams.toList
import io.ktor.client.features.cookies.AcceptAllCookiesStorage
import io.ktor.client.features.cookies.HttpCookies


//TODO: configure
private const val HOST = "localhost:9000"
private const val RESULTS_DIR = "/home/mikael0/ASRs/asrtester/out"
private const val SAMPLES_DIR = "/home/mikael0/ASRs/ASRTestingFramework/asr_audio_samples"
private const val testLowerFactor = 0.1
private const val testUpperFactor = 10
private const val testRangeFactor = testUpperFactor - testLowerFactor
private const val retryTimeoutMs = 15000L

/**
 * Usage
 * argv[1] - name of tested asrs
 * argv[2] - number of test steps - 1
 * argv[3] - dataset key
 * argv[4] - maximum timeout in seconds
 * argv[5] - dir for results
 * argv[6] - dir with samples
 * argv[7] - host with system
 * argv[8] - path to file with excluded parameters (one by line)
 * argv[9] - path to file with predefined parameters (key:value by line)
 * argv[10] - one experiment repeat count
 */

//create job
data class ASRJobStep(val index: Int,
                      val stepType: String = "test",
                      val config: Map<String, String> = emptyMap(),
                      val modelType: String = "default",
                      val jobForModel: String? = null,
                      val samples: Array<String>)
data class ASRJob(val asrKey: String, val steps: Array<ASRJobStep> )
data class ASRJobStepResp(val id: String)
data class ASRJobResp(val id: String, val steps: Array<ASRJobStepResp>)

//get job result
data class ASRJobResult(val result: Map<String, String>)

//get asr params
data class ASRParamOption(val title: String, val key: String)
data class ASRParam(val key: String, val paramType: String, val defaultValue: String, val options: Array<ASRParamOption>?)
data class ASRTestParams(val test: Array<ASRParam>)

//auth
data class Credentials(val username: String, val password: String)

private val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()

fun main(args: Array<String>) = runBlocking {
    if (args.size == 0) {
        print("""
            Usage
            argv[1] - name of tested asrs
            argv[2] - number of test steps - 1
            argv[3] - dataset key
            argv[4] - maximum timeout in seconds
            argv[5] - dir for results
            argv[6] - dir with samples
            argv[7] - host with system
            argv[8] - path to file with excluded parameters (one by line)
            argv[9] - path to file with predefined parameters (key:value by line)
            argv[10] - one experiment repeat count
        """.trimIndent())
    }
    val asrName = args[0]
    val experimentCount = args[1].toInt()
    val datasetKey = args[2]
    val timeout = args[3].toInt() * 1000
    val resultsDir = if (args.size >= 5) args[4] else RESULTS_DIR
    val samplesDir = if (args.size >= 6) args[5] else SAMPLES_DIR
    val host = if (args.size >= 7) args[6] else HOST
    val excludedPath = if (args.size >= 8) args[7] else ""
    val predefinedPath = if (args.size >= 9) args[8] else ""
    val repeatCount = if (args.size >= 10) args[9].toInt() else 1

    File(resultsDir).mkdirs()
    val datasetType = File(Paths.get(samplesDir, datasetKey, "info.meta")
            .toUri())
            .readLines()
            .filter {it.contains("datasetType")}
            .map { it.replace("datasetType=", "") }
            .get(0)
    val dataset = if (datasetType.contains("voxforge")) {
                     Files.list(Paths.get(samplesDir, datasetKey))
                        .filter { !it.toString().contains(".meta") }
                        .map { Files.list(Paths.get(it.toString(), "wav")).toList() }
                        .toList()
                        .flatten()
                        .map {
                            it.toString()
                                    .replace(samplesDir + "/", "")
                                    .replace("wav", "")
                                    .replace("//", "-")
                                    .replace(".", "")
                        }.toTypedArray()
                } else {
                     Files.list(Paths.get(samplesDir, datasetKey))
                             .map {  it.toString().replace(samplesDir + "/", "") }
                             .toArray { size -> Array(size, { "" }) }
                }

    val client = HttpClient(Apache) {
        install(JsonFeature) {
            serializer = GsonSerializer {
                serializeNulls()
                disableHtmlEscaping()
            }
        }
        install(HttpCookies) {
            storage = AcceptAllCookiesStorage()
        }
    }

    try {
        try {
            client.post<String> {
                url("http://$host/api/login/")
                contentType(ContentType.Application.Json)
                body = Credentials("root", "4dE^-c=LxTKW=zDQ")
            }
        } catch (e: Exception) {
            println("Failed to login $e")
        }

        val asrParams = client.get<ASRTestParams> {
            url("http://$host/api/asr/executors/$asrName/params/")
            contentType(ContentType.Application.Json)
        }

        println("Got params for ASR $asrName\n$asrParams")
        val excludedParams = File(excludedPath).readLines()
        val predefinedRaw = File(predefinedPath).readLines()
        val predefinedParams = predefinedRaw.map { it.substringBefore(':') to it.substringAfter(':')}.toMap()
        for (param in asrParams.test) {
            if (param.paramType == "string" || param.key == "modelName") {
                continue
            }

            println("Testing ${param.key}")
            if (param.key in excludedParams) {
                println("${param.key} is excluded")
                continue
            }
            val writer = Files.newBufferedWriter(Paths.get("$resultsDir/${asrName}_${param.key}.json"))
            val reportJson = JsonArray()
            for (testedValue in when(param.paramType) {
                "int" -> param.defaultValue.toInt().let {
                    var stp = (it * testRangeFactor).toInt() / experimentCount
                    if (stp == 0) {
                        stp = 1
                    }
                    (it * testLowerFactor).toInt() .. (it * testUpperFactor).toInt() step stp
                }
                "double", "float" -> param.defaultValue.toDouble().let {
                    (it * testLowerFactor) .. (it * testUpperFactor) step it * testRangeFactor / experimentCount
                }
                "select" -> param.options?.map { it.key } ?: IntRange.EMPTY
                "Boolean", "boolean", "bool" -> listOf(true, false)
                else -> IntRange.EMPTY
            }) {

                try {
                    println("Testing ${param.key} : $testedValue")
                    for (i in 0..repeatCount) {
                        val config = asrParams.test.map {
                            if (param.key != it.key) {
                                it.key to (predefinedParams.get(it.key) ?: it.defaultValue)
                            } else {
                                it.key to testedValue.toString()
                            }
                        }.toMap()

                        val response = client.post<ASRJobResp> {
                            url("http://$host/api/asr/jobs/")
                            contentType(ContentType.Application.Json)
                            body = ASRJob(asrName, Array(1) {
                                ASRJobStep(it, config = config, samples = dataset)
                            })
                        }
                        println("Created job $response")
                        val id = response.steps[0].id

                        var retries = 0
                        var completed = false
                        do {
                            delay(retryTimeoutMs)
                            try {
                                val resStatus = client.get<ASRJobResult> {
                                    url("http://$host/api/asr/jobs/result/$id/")
                                    contentType(ContentType.Application.Json)
                                }
                                println("Job results $resStatus")
                                completed = true
                                val reportItem = JsonObject()
                                reportItem.addProperty("testedValue", testedValue.toString())
                                for (item in resStatus.result) {
                                    reportItem.addProperty(item.key, item.value)
                                }
                                reportJson.add(reportItem)
                            } catch (e: ClientRequestException) {
                                println("Error $e")
                                retries++
                            }
                        } while (!(completed || retries > timeout / retryTimeoutMs))
                    }
                } catch(e: ClientRequestException) {
                    println("Error $e")
                }
            }
            writer.write(reportJson.toString())
            writer.flush()
            writer.close()
        }
    } catch (e: Exception) {
        println("Error $e")
    }

    client.close()
}

private infix fun ClosedRange<Double>.step(step: Double): Iterable<Double> {
    require(start.isFinite())
    require(endInclusive.isFinite())
    require(step > 0.0) { "Step must be positive, was: $step." }
    val sequence = generateSequence(start) { previous ->
        if (previous == Double.POSITIVE_INFINITY) return@generateSequence null
        val next = previous + step
        if (next > endInclusive) null else next
    }
    return sequence.asIterable()
}

