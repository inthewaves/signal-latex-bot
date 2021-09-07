package signallatexbot.core

import org.scilab.forge.jlatexmath.TeXConstants
import org.scilab.forge.jlatexmath.TeXFormula
import signallatexbot.util.SemVer
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Insets
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOInvalidTreeException
import javax.imageio.metadata.IIOMetadata
import javax.imageio.metadata.IIOMetadataNode
import javax.print.attribute.ResolutionSyntax.DPI
import javax.swing.JLabel
import kotlin.math.roundToInt
import kotlin.time.TimeSource
import kotlin.time.measureTime
import kotlin.time.measureTimedValue


interface LatexGenerator {
    fun writeLatexToPng(latexString: String, outputFile: File)
}

class PodmanLatexGenerator : LatexGenerator {
    init {
        val podmanVersionProcess = ProcessBuilder(
            "podman", "--log-level", "debug",  "version", "-f", "{{.Client.Version}}"
        ).start()
        val versionText = podmanVersionProcess.inputStream?.bufferedReader()?.use { it.readText() }
        if (!podmanVersionProcess.waitFor(1L, TimeUnit.SECONDS)) {
            error("took too long to get Podman version")
        }
        if (podmanVersionProcess.exitValue() != 0 || versionText == null) {
            val error = try {
                podmanVersionProcess.errorStream?.bufferedReader()?.use { it.readText() }
            } catch (e: IOException) {
                null
            }
            error("failed to get Podman version: exit value ${podmanVersionProcess.exitValue()}, error: $error, stdout: $versionText")
        }

        val minimumPodmanVersion = SemVer(3, 3, 0)
        val podmanVersion = SemVer.parse(versionText.trim())
        require (podmanVersion >= minimumPodmanVersion) {
            "current Podman version ($podmanVersion) is less than the minimum version ($minimumPodmanVersion)"
        }
    }

    private val seccompJsonFile = createSeccompFile()

    private fun createSeccompFile(): File {
        return Files.createTempFile("latexseccomp", ".json")
            .toFile()
            .apply {
                deleteOnExit()
                // setPosixPermissions("r--r--r--")
                // writeText(LATEX_SECCOMP)
            }
    }

    override fun writeLatexToPng(latexString: String, outputFile: File) {
        if (BLOCKED.containsMatchIn(latexString)) {
            println("warning: blocked string detected")
        }

        val tempTexDir = Files.createTempDirectory("temp-test-${System.currentTimeMillis()}")
            .toFile()
            .apply {
                deleteOnExit()
                if (!exists() && !mkdirs()) throw IOException("Unable to make dir")
            }
        try {
            val tempTexFile = File(tempTexDir, "$BASE_FILENAME.tex")
            writeTexFile(latexString, tempTexFile)

            val seccompFileToUse = seccompJsonFile.takeIf { it.canRead() } ?: createSeccompFile()
            val latexProcessBuilder = ProcessBuilder(
                "podman", "--log-level", "debug", "run",
                "--cap-drop", "all",
                "--net", "none",
                "--userns", "keep-id",
                //"--security-opt", "seccomp=/opt/signallatexbot/latex-sandbox/podman-latex.json",
                "--security-opt", "label=$SELINUX_LABEL",
                "--rm",
                "--read-only",
                "--memory", "${MAX_MEMORY_MEGABYTES}m",
                "--cpus", "0.25",
                "--timeout", "5",
                "-v", "${tempTexDir.absolutePath}:/data",
                IMAGE_NAME,
                "/bin/bwrap-latex", "-no-shell-escape", "-no-parse-first-line", "-interaction=nonstopmode",
                "-halt-on-error", tempTexFile.name
            )
            val latexProcess = latexProcessBuilder.start()
            latexProcess.waitFor()
            val output = latexProcess.inputStream?.bufferedReader()?.use { it.readText() }
            val stdErrOutput = latexProcess.errorStream?.bufferedReader()?.use { it.readText() }
            println("Output: $output")
            println("StdErr: $stdErrOutput")
            if (latexProcess.exitValue() != 0) {
                throw IOException("non-successful exit value: ${latexProcess.exitValue()}. Command is [${latexProcessBuilder.command().joinToString(" ")}]")
            }
            tempTexDir.walkTopDown().forEach { println("$it") }

            val dvipngProcess = ProcessBuilder(
                "podman", "--log-level", "debug", "run",
                "--cap-drop", "all",
                "--net", "none",
                "--userns", "keep-id",
                //"--security-opt", "seccomp=/opt/signallatexbot/latex-sandbox/podman-dvipng.json",
                "--security-opt", "label=$SELINUX_LABEL",
                "--rm",
                "--read-only",
                "--memory", "${MAX_MEMORY_MEGABYTES}m",
                "--cpus", "0.25",
                "--timeout", "5",
                "-v", "${tempTexDir.absolutePath}:/data",
                IMAGE_NAME,
                "/bin/bwrap-dvipng", "-D", "800", "-T", "tight", BASE_DVI_FILENAME, "-o", BASE_PNG_FILENAME
            ).start()
            dvipngProcess.waitFor()
            val outputDvi = dvipngProcess.inputStream?.bufferedReader()?.use { it.readText() }
            val errDvi = dvipngProcess.errorStream?.bufferedReader()?.use { it.readText() }
            println("Output for dvipng: $outputDvi")
            println("StdErr for dvipng: $errDvi")
            if (dvipngProcess.exitValue() != 0) {
                throw IOException("non-successful exit value for dvipng: ${dvipngProcess.exitValue()}")
            }
            tempTexDir.walkTopDown().forEach { println("$it") }

            val pngInTmp = File(tempTexDir, BASE_PNG_FILENAME)
            if (!pngInTmp.canRead()) {
                throw IOException("can't read output PNG")
            }
            pngInTmp.copyTo(outputFile, overwrite = true)
        } finally {
            // tempTexDir.deleteRecursively()
        }
    }

    companion object {
        private val BLOCKED: Regex = run {
            val blocked = sequenceOf(
                "catcode", "usepackage", "include", "csname", "endcsname", "input", "jobname", "newread", "openin",
                "read", "readline", "relax", "write"
            )

            Regex("""(\\@{0,2}(${blocked.joinToString("|")}))|(\^\^5c)""")
        }

        private val PREAMBLE = """
            \documentclass[12pt]{article}
            \usepackage{amsmath}
            \usepackage{amssymb}
            \usepackage{amsfonts}
            \usepackage{xcolor}
            \usepackage{siunitx}
            \usepackage[utf8]{inputenc}
            \thispagestyle{empty}
        """.trimIndent()
        private const val OPENING = """\begin{document}"""
        private const val ENDING = """\end{document}"""
        private const val IMAGE_NAME = "localhost/latex-bwrap-minimal"
        private const val BASE_FILENAME = "eqn"
        private const val BASE_TEX_FILENAME = "$BASE_FILENAME.tex"
        private const val BASE_DVI_FILENAME = "$BASE_FILENAME.dvi"
        private const val BASE_PNG_FILENAME = "$BASE_FILENAME.png"
        private const val SELINUX_LABEL = "type:latex_container.process"

        private const val MAX_MEMORY_MEGABYTES = 100

        private val LATEX_COMMAND = """
            set -o errexit
            
            echo 'openout_any = p\nopenin_any = p' > /tmp/texmf.cnf
            export TEXMFCNF='/tmp:'
            # Compile .tex file to .dvi file. Timeout kills it after 5 seconds if held up
            timeout 5 latex -no-shell-escape -no-parse-first-line -interaction=nonstopmode -halt-on-error $BASE_TEX_FILENAME
            dvipng -D 800 -T tight $BASE_DVI_FILENAME -o $BASE_PNG_FILENAME
        """.trimIndent()

        private const val LATEX_SECCOMP_PATH = "/opt/signallatexbot/podman-latex.json"
        private const val DVIPNG_SECCOMP_PATH = "/opt/signallatexbot/podman-dvipng.json"

        private fun writeTexFile(latexString: String, texFile: File) {
            texFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                with(writer) {
                    write(PREAMBLE)
                    newLine()
                    write(OPENING)
                    newLine()
                    write("""\[$latexString\]""")
                    newLine()
                    write(ENDING)
                    newLine()
                }
            }
        }
    }
}

class JLaTeXMathGenerator : LatexGenerator {
    private fun calculatePercentage(max: Float): Double = when {
        max <= 2500f -> 0.01
        max <= 4000f -> 0.05
        max <= 5000f -> 0.10
        max <= 6000f -> 0.15
        max <= 7000f -> 0.20
        else -> 0.30
    }

    override fun writeLatexToPng(latexString: String, outputFile: File) {
        require(!outputFile.isDirectory) { "output file can't be a directory" }

        val (formula, formulaTime) = measureTimedValue { TeXFormula(latexString) }
        val (icon, iconTime) = measureTimedValue {
            formula.createTeXIcon(TeXConstants.STYLE_DISPLAY, 100f).apply {
                val max = maxOf(trueIconHeight, trueIconWidth)
                val min = minOf(trueIconHeight, trueIconWidth)
                val percentage = calculatePercentage(max)
                val useMobileFriendlyInsets = min >= percentage * max
                println(
                    "Existing insets=$insets, min=$min, max=$max, percentage=$percentage, " +
                            "useMobileFriendlyInsets=$useMobileFriendlyInsets, " +
                            "trueIconWidth=$trueIconWidth, trueIconHeight=$trueIconHeight"
                )
                insets = if (useMobileFriendlyInsets) {
                    // trueIconWidth + 2*(horizontalInset) = max
                    val horizontalInset = ((max - trueIconWidth) / 2.0)
                        .coerceAtLeast(0.0)
                        .let { if (it in 0.0..0.005) 0.05 * max else it }
                        .roundToInt()
                    val verticalInset = ((max - trueIconHeight) / 2.0 - 1.5 * percentage * max)
                        // Signal has footers for images that can obscure the image
                        .coerceAtLeast(0.15 * trueIconHeight)
                        .roundToInt()
                    println("horizontalInset=$horizontalInset, verticalInset=$verticalInset")
                    Insets(verticalInset, horizontalInset, verticalInset, horizontalInset)
                } else {
                    val verticalInsets = maxOf(insets.top, insets.bottom)
                        .coerceAtLeast((0.30 * trueIconHeight).roundToInt())
                    Insets(verticalInsets, insets.left, verticalInsets, insets.right)
                }
            }
        }

        val (bufferedImage, bufferedImageTime) = measureTimedValue {
            BufferedImage(icon.iconWidth, icon.iconHeight, BufferedImage.TYPE_INT_ARGB)
        }
        val (graphics, graphicsTime) = measureTimedValue {
            bufferedImage.createGraphics().apply {
                // FIXME: This doesn't work
                val transparentBackground = true
                if (transparentBackground) {
                    composite = AlphaComposite.Src
                } else {
                    color = Color.white
                }
                fillRect(0, 0, icon.iconWidth, icon.iconHeight)
            }
        }
        try {
            val jLabel = JLabel().apply { foreground = Color.black }
            val paintTime = measureTime { icon.paintIcon(jLabel, graphics, 0, 0) }

            println(
                "Times: formula: $formulaTime, icon: $iconTime, buffImg: $bufferedImageTime, " +
                        "graphics: $graphicsTime, paint: $paintTime"
            )
            // ImageIO.write(bufferedImage, "png", outputFile)
            saveGridImage(outputFile, bufferedImage)
        } finally {
            graphics.dispose()
        }
    }

    /**
     * https://stackoverflow.com/a/4833697
     */
    @Throws(IOException::class)
    private fun saveGridImage(output: File, gridImage: BufferedImage) {
        output.delete()
        val start = TimeSource.Monotonic.markNow()
        for (writer in ImageIO.getImageWritersByFormatName("png")) {
            val writeParam = writer.defaultWriteParam
            val typeSpecifier = ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_ARGB)
            val metadata = writer.getDefaultImageMetadata(typeSpecifier, writeParam)
            if (metadata.isReadOnly || !metadata.isStandardMetadataFormatSupported) {
                continue
            }
            val writerFindTime = start.elapsedNow()
            val dpiTime = measureTime { setDPI(metadata) }

            val (stream, streamTime) = measureTimedValue { ImageIO.createImageOutputStream(output) }
            val writeTime = measureTime {
                stream.use { str ->
                    writer.output = str
                    writer.write(metadata, IIOImage(gridImage, null, metadata), writeParam)
                }
            }
            println("Times: writer find: $writerFindTime, dpi: $dpiTime, stream: $streamTime, write: $writeTime")
            return
        }
        throw IOException("unable to find any image writers")
    }

    /**
     * https://stackoverflow.com/a/4833697
     */
    @Throws(IIOInvalidTreeException::class)
    private fun setDPI(metadata: IIOMetadata) {
        // for PMG, it's dots per millimeter
        val dotsPerMilli: Double = 1.0 * DPI / 10 / INCH_2_CM
        val horiz = IIOMetadataNode("HorizontalPixelSize")
        horiz.setAttribute("value", dotsPerMilli.toString())
        val vert = IIOMetadataNode("VerticalPixelSize")
        vert.setAttribute("value", dotsPerMilli.toString())
        val dim = IIOMetadataNode("Dimension")
        dim.appendChild(horiz)
        dim.appendChild(vert)
        val root = IIOMetadataNode("javax_imageio_1.0")
        root.appendChild(dim)
        metadata.mergeTree("javax_imageio_1.0", root)
    }

    companion object {
        private const val INCH_2_CM = 2.54
    }
}