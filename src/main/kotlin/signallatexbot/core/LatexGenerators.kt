package signallatexbot.core

import org.scilab.forge.jlatexmath.TeXConstants
import org.scilab.forge.jlatexmath.TeXFormula
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Insets
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
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