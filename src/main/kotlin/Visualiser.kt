import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import java.nio.file.Files
import java.nio.file.Path

private val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()

fun main(args: Array<String>) {

    val inDir = args[0]
    val outDir = args[1]

    for (file in Files.list(Path.of(inDir))) {
        val jsonStr = Files.readString(file)
        val paramter = file.fileName.toString().substringAfter("_").replace(".json", "")

        val json = gson.fromJson(jsonStr, JsonArray::class.java)
        try {
            for (metric in json.get(0).asJsonObject.entrySet().filter { it.key != "testedValue" }.map { it.key }) {
                val visFile = Path.of("${outDir}/diagram_${paramter}_${metric}.html")
                with(Files.newBufferedWriter(visFile)) {
                    write("""
                            <html xmlns="http://www.w3.org/1999/xhtml">
                            <meta charset="UTF-8">
                            <head>
                                <script type="text/javascript" src="https://canvasjs.com/assets/script/canvasjs.min.js"> </script>
                                <script src="https://ajax.googleapis.com/ajax/libs/jquery/3.4.1/jquery.min.js"></script>
                                <script type="text/javascript">
                                    ${'$'}(document).ready(function(){
                                        var jsonObj = ${jsonStr}
                                        var parameter = "${paramter}"
                                        var metric = "${metric}"

                                        // formatting the json data as required by CanvasJS
                                        var dataPoints = [];
                                        var maxValue = 0
                                        var labels = [];

                                        for (var i = 0; i < jsonObj.length; i++) {
                                            if (isNaN(parseFloat(jsonObj[i][metric]))) {
                                                continue
                                            }
                                            if (labels.includes(jsonObj[i].testedValue)) {
                                                continue
                                            }
                                            labels.push(jsonObj[i].testedValue)
                                            maxValue = Math.max(maxValue, parseFloat(jsonObj[i][metric]))
                                            dataPoints.push({ label: jsonObj[i].testedValue, y: parseFloat(jsonObj[i][metric])});
                                        }
                                        console.log(dataPoints)

                                        var chart = new CanvasJS.Chart("chartContainer",
                                            {
                                                subtitles: [{
                                                    text: parameter,
                                                    fontSize: 16
                                                }],
                                                data: [
                                                    {
                                                        type: "column",
                                                        dataPoints: dataPoints
                                                    }
                                                ],
                                                axisY: {
                                                    title: metric,
                                                    maximum: maxValue * 1.3
                                                }
                                            });

                                        chart.render();
                                    });
                                </script>
                            </head>
                            <body>
                                <div id="chartContainer" style="height: 360px; width: 100%;"></div>
                            </body>
                        </html>
                    """)
                    close()
                }
            }
        } catch (e: Exception) {
            println("$e in $file")
        }
    }
}