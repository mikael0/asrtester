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
import java.lang.NumberFormatException
import java.util.*


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
open class ASRJobStep
data class TagGroup(val metrics: Array<String>, val tags: Array<String>)
data class MetricsStepConfig(val metrics: Array<String>, val tagGroups: Array<TagGroup> = emptyArray())
data class ASRJobTestStep(val index: Int,
                      val stepType: String = "test",
                      val config: Map<String, String> = emptyMap(),
                      val inputType: String = "default",
                      val inputJob: String? = null,
                      val samples: Array<String>) : ASRJobStep()
data class ASRJobMeasureStep(val index: Int,
                      val stepType: String = "measure",
                      val config: MetricsStepConfig,
                      val samples: Array<String>,
                      val inputJob: String? = null,
                      val inputType: String = "current") : ASRJobStep()
data class ASRJob(val asrKey: String, val steps: Array<ASRJobStep> )
data class ASRJobStepResp(val id: String)
data class ASRJobResp(val id: String, val steps: Array<ASRJobStepResp>)

//get job result
data class ASRJobResult(val result: ASRJobResultInner)
data class ASRJobResultInner(val overall: Array<OverallMetrics>)
data class OverallMetrics(val result: Map<String, String>)

//get asr params
data class ASRParamOption(val title: String, val key: String)
data class ASRParam(val key: String, val paramType: String, val defaultValue: String, val options: Array<ASRParamOption>?)
data class ASRTestParams(val test: Array<ASRParam>)

//auth
data class Credentials(val username: String, val password: String)

//metrics
data class Metric(val key: String, val title: String)

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
                        .filter { !it.toString().contains(".txt") }
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
    println("Dataset size ${dataset.size}")
    println("Dataset: ${Arrays.toString(dataset)}")

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
                body = Credentials("root", "root")
            }
        } catch (e: Exception) {
            println("Failed to login $e")
        }

        val metrics = client.get<Array<Metric>> {
            url("http://$host/api/asr/metrics/")
            contentType(ContentType.Application.Json)
        }
        println("Got metrics $metrics")
//        val metricsConfig = metrics.map {
//            it.key
//        }.toTypedArray()

        val metricsConfig = arrayOf("SF", "WER", "INS", "DEL", "SUB", "CHER", "PHER", "CER",
                "CXER", "INFLER", "INFLERA", "IMERA", "NCR", "OCWR",
                "IWERA", "IWER", "HES", "RER", "WMER", "WLMER")

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
                    val aveValues = mutableMapOf<String, MutableList<Float>>()
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
                            body = ASRJob(asrName, Array(2) {
                                when(it) {
                                    0 -> ASRJobTestStep(it + 2, config = config, samples = dataset)
                                            as ASRJobStep
                                    1 -> ASRJobMeasureStep(it  + 2, config = MetricsStepConfig(metricsConfig),
                                            samples = emptyArray())
                                            as ASRJobStep
                                    else -> null as ASRJobStep
                                }
                            })
                        }
                        println("Created job $response")
                        val id = response.steps[0].id
                        val metricsId = response.steps[1].id

                        var retries = 0
                        var completed = false
                        do {
                            delay(retryTimeoutMs)
                            try {
                                var resStatus = client.get<ASRJobResult> {
                                    url("http://$host/api/asr/jobs/result/$id/")
                                    contentType(ContentType.Application.Json)
                                }
                                println("Job results $resStatus")
                                delay(5000)
                                resStatus = client.get<ASRJobResult> {
                                    url("http://$host/api/asr/jobs/result/$metricsId/")
                                    contentType(ContentType.Application.Json)
                                }
                                println("Job results $resStatus")
                                completed = true
                                for (item in resStatus.result.overall[0].result) {
                                    try {
                                        aveValues.get(item.key)?.apply {
                                            add(item.value.toFloat())
                                        } ?: aveValues.put(item.key, mutableListOf(item.value.toFloat()))
                                    } catch (ignored: NumberFormatException) { }
                                }
                            } catch (e: ClientRequestException) {
                                println("Error $e")
                                retries++
                            }
                        } while (!(completed || retries > timeout / retryTimeoutMs))
                    }
                    val reportItem = JsonObject()
                    reportItem.addProperty("testedValue", testedValue.toString())
                    aveValues.forEach { reportItem.addProperty(it.key, it.value.average()) }
                    reportJson.add(reportItem)
                } catch (e: ClientRequestException) {
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

