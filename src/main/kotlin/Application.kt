import com.google.gson.GsonBuilder
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

//TODO: configure
private const val HOST = "localhost:9000"
private const val RESULTS_DIR = "/home/mikael0/ASRs/asrtester/out"
private const val SAMPLES_DIR = "/home/mikael0/ASRs/ASRTestingFramework/asr_audio_samples"
private const val testLowerFactor = 0.1
private const val testUpperFactor = 10
private const val testRangeFactor = testUpperFactor - testLowerFactor

/**
 * Usage
 * argv[1] - name of tested asrs
 * argv[2] - number of test steps - 1
 * argv[3] - dataset key
 * argv[4] - dir for results (optional)
 * argv[5] - dir with samples (optional)
 * argv[6] - host with system (optional)
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
data class ASRParamOption(val title: String)
data class ASRParam(val key: String, val paramType: String, val defaultValue: String, val options: Array<ASRParamOption>?)
data class ASRTestParams(val test: Array<ASRParam>)

private val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()

fun main(args: Array<String>) = runBlocking {
    val asrName = args[0]
    val experimentCount = args[1].toInt()
    val datasetKey = args[2]
    val resultsDir = if (args.size >= 4) args[3] else RESULTS_DIR
    val samplesDir = if (args.size >= 5) args[4] else SAMPLES_DIR
    val host = if (args.size >= 6) args[5] else HOST

    File(resultsDir).mkdirs()
    val dataset = Files.list(Paths.get(samplesDir, datasetKey))
            .map { it.toString().replace(samplesDir + "/", "") }
            .toArray{ size -> Array(size, { "" }) }

    val client = HttpClient(Apache) {
        install(JsonFeature) {
            serializer = GsonSerializer {
                serializeNulls()
                disableHtmlEscaping()
            }
        }
    }
    try {
        val asrParams = client.get<ASRTestParams> {
            url("http://$host/api/asr/executors/$asrName/params/")
            contentType(ContentType.Application.Json)
        }

        println("Got params for ASR $asrName\n$asrParams")
        for (param in asrParams.test) {
            if (param.paramType == "string" || param.key == "modelName") {
                continue
            }

            for (testedValue in when(param.paramType) {
                "int" -> param.defaultValue.toInt().let {
                    (it * testLowerFactor).toInt() .. (it * testUpperFactor).toInt() step (it * testRangeFactor).toInt() / experimentCount
                }
                "double", "float" -> param.defaultValue.toDouble().let {
                    (it * testLowerFactor) .. (it * testUpperFactor) step it * testRangeFactor / experimentCount
                }
                "select" -> param.options?.map { it.title } ?: IntRange.EMPTY
                else -> IntRange.EMPTY
            }) {
                try {
                    val config = asrParams.test.map {
                        if (param.key != it.key)
                            it.key to it.defaultValue
                        else
                            it.key to testedValue.toString()
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

                    do {
                        var retries = 0
                        var completed = false
                        delay(15000)
                        try {
                            val resStatus = client.get<ASRJobResult> {
                                url("http://$host/api/asr/jobs/result/$id/")
                                contentType(ContentType.Application.Json)
                            }
                            println("Job results $resStatus")
                            completed = true
                            val writer = Files.newBufferedWriter(Paths.get("$resultsDir/${asrName}_${param.key}_${testedValue}.json"))
                            writer.write(gson.toJson(resStatus.result))
                            writer.flush()
                            writer.close()
                        } catch (e: ClientRequestException) {
                            println("Error $e")
                            retries++
                        }
                    } while (!(completed || retries > 6))
                } catch(e: ClientRequestException) {
                    println("Error $e")
                }
            }
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

