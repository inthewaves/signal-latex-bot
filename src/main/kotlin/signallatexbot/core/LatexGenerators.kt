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


interface LatexGenerator {
    fun writeLatexToPng(latexString: String, outputFile: File)
}

class JLaTeXMathGenerator : LatexGenerator {
    override fun writeLatexToPng(latexString: String, outputFile: File) {
        require(!outputFile.isDirectory) { "output file can't be a directory" }

        val formula = TeXFormula(latexString)
        val icon = formula.createTeXIcon(TeXConstants.STYLE_DISPLAY, 100f).apply {
            val insetSize = 50
            insets = Insets(insetSize, insetSize, insetSize, insetSize)
        }

        val bufferedImage = BufferedImage(icon.iconWidth, icon.iconHeight, BufferedImage.TYPE_INT_ARGB)
        val graphics = bufferedImage.createGraphics().apply {
            // FIXME: This doesn't work
            val transparentBackground = true
            if (transparentBackground) {
                composite = AlphaComposite.Src
            } else {
                color = Color.white
            }
            fillRect(0, 0, icon.iconWidth, icon.iconHeight)
        }
        try {
            val jLabel = JLabel().apply { foreground = Color.black }
            icon.paintIcon(jLabel, graphics, 0, 0)
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
        for (writer in ImageIO.getImageWritersByFormatName("png")) {
            val writeParam = writer.defaultWriteParam
            val typeSpecifier = ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_ARGB)
            val metadata = writer.getDefaultImageMetadata(typeSpecifier, writeParam)
            if (metadata.isReadOnly || !metadata.isStandardMetadataFormatSupported) {
                continue
            }
            setDPI(metadata)
            val stream = ImageIO.createImageOutputStream(output)
            stream.use { str ->
                writer.output = str
                writer.write(metadata, IIOImage(gridImage, null, metadata), writeParam)
            }
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