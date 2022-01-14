package signallatexbot.core

import org.scilab.forge.jlatexmath.TeXConstants
import org.scilab.forge.jlatexmath.TeXFormula
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Insets
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.nio.file.Files
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.ImageWriteParam
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
  private val seccompJsonFile = createSeccompFile()

  private fun createSeccompFile(): File {
    return Files.createTempFile("latexseccomp", ".json")
      .toFile()
      .apply {
        deleteOnExit()
        // setPosixPermissions("r--r--r--")
        writeText(LATEX_SECCOMP)
      }
  }

  override fun writeLatexToPng(latexString: String, outputFile: File) {
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
      val process = ProcessBuilder(
        "podman", "run",
        "--cap-drop", "all",
        "--net", "none",
        "--security-opt", "seccomp=${seccompFileToUse.absolutePath}",
        "--rm",
        "--read-only",
        "--memory", "${MAX_MEMORY_MEGABYTES}m",
        "--name", "LOL",
        "--cpus", "0.25",
        "--timeout", "5",
        "-v", "${tempTexDir.absolutePath}:/data",
        IMAGE_NAME,
        "/bin/sh", "-c", LATEX_COMMAND
      ).start()
      process.waitFor()
      val output = process.inputStream?.bufferedReader()?.use { it.readText() }
      val stdErrOutput = process.errorStream?.bufferedReader()?.use { it.readText() }
      println("Output: $output")
      println("StdErr: $stdErrOutput")
      if (process.exitValue() != 0) {
        throw IOException("non-successful exit value: ${process.exitValue()}")
      }
      tempTexDir.walkTopDown().forEach {
        println("$it")
      }

      val pngInTmp = File(tempTexDir, BASE_PNG_FILENAME)
      if (!pngInTmp.canRead()) {
        throw IOException("can't read output PNG")
      }
      pngInTmp.copyTo(outputFile, overwrite = true)
    } finally {
      tempTexDir.deleteRecursively()
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
    private const val IMAGE_NAME = "docker.io/blang/latex:ubuntu"
    private const val BASE_FILENAME = "eqn"
    private const val BASE_TEX_FILENAME = "$BASE_FILENAME.tex"
    private const val BASE_DVI_FILENAME = "$BASE_FILENAME.dvi"
    private const val BASE_PNG_FILENAME = "$BASE_FILENAME.png"

    private const val MAX_MEMORY_MEGABYTES = 100

    private val LATEX_COMMAND = """
            set -o errexit
            
            echo 'openout_any = p\nopenin_any = p' > /tmp/texmf.cnf
            export TEXMFCNF='/tmp:'
            # Compile .tex file to .dvi file. Timeout kills it after 5 seconds if held up
            timeout 5 latex -no-shell-escape -interaction=nonstopmode -halt-on-error $BASE_TEX_FILENAME
            dvipng -D 800 -T tight $BASE_DVI_FILENAME -o $BASE_PNG_FILENAME
    """.trimIndent()

    /**
     * Generated from these steps (on Fedora 33):
     *
     *     $ curl -O https://kojipkgs.fedoraproject.org//packages/oci-seccomp-bpf-hook/1.2.3/1.fc33/x86_64/oci-seccomp-bpf-hook-1.2.3-1.fc33.x86_64.rpm
     *     $ sudo dnf install oci-seccomp-bpf-hook-1.2.3-1.fc33.x86_64.rpm
     *     $ sudo podman run --annotation io.containers.trace-syscall="of:$PWD/latexseccomp.json" \
     *           --net none \
     *           -v $HOME/sandbox:/data \
     *           "docker.io/blang/latex:ubuntu" /bin/sh -c "$containerCmds"
     *
     * More information: [https://www.redhat.com/sysadmin/container-security-seccomp]
     */
    private val LATEX_SECCOMP = """
            {
              "defaultAction" : "SCMP_ACT_ERRNO",
              "syscalls" : [
                {
                  "includes" : {},
                  "action" : "SCMP_ACT_ALLOW",
                  "excludes" : {},
                  "names" : [
                    "",
                    "access",
                    "arch_prctl",
                    "brk",
                    "capset",
                    "chdir",
                    "clone",
                    "close",
                    "dup2",
                    "execve",
                    "exit_group",
                    "fchdir",
                    "fcntl",
                    "fstat",
                    "fstatfs",
                    "getcwd",
                    "getdents",
                    "getdents64",
                    "getegid",
                    "geteuid",
                    "getgid",
                    "getpid",
                    "getppid",
                    "getrlimit",
                    "gettimeofday",
                    "getuid",
                    "lseek",
                    "lstat",
                    "mmap",
                    "mount",
                    "mprotect",
                    "munmap",
                    "open",
                    "openat",
                    "pivot_root",
                    "prctl",
                    "read",
                    "readlink",
                    "rt_sigaction",
                    "rt_sigprocmask",
                    "rt_sigreturn",
                    "seccomp",
                    "select",
                    "set_robust_list",
                    "set_tid_address",
                    "sethostname",
                    "setpgid",
                    "setresgid",
                    "setresuid",
                    "setsid",
                    "stat",
                    "statx",
                    "sysinfo",
                    "timer_create",
                    "timer_settime",
                    "umask",
                    "umount2",
                    "unlink",
                    "wait4",
                    "write"
                  ],
                  "comment" : "",
                  "args" : []
                }
              ],
              "architectures" : [
                "SCMP_ARCH_X86_64"
              ]
            }
    """.trimIndent()

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
    max <= 3000f -> 0.01
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
            "useMobileFriendlyInsets=$min >= ${percentage * max} == $useMobileFriendlyInsets, " +
            "trueIconWidth=$trueIconWidth, trueIconHeight=$trueIconHeight"
        )
        insets = if (useMobileFriendlyInsets) {
          // trueIconWidth + 2*(horizontalInset) = max
          val horizontalInset = ((max - trueIconWidth) / 2.0)
            .coerceAtLeast(0.05 * max)
            .roundToInt()
          val verticalInset = ((max - trueIconHeight) / 2.0)
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

    // getImageWritersByFormatName will create new writers, making it thread-safe.
    for (writer in ImageIO.getImageWritersByFormatName("png")) {
      println("Writer class: ${writer::class.simpleName}")
      try {
        val writeParam = writer.defaultWriteParam
        if (!writeParam.canWriteCompressed()) continue
        writeParam.apply {
          compressionMode = ImageWriteParam.MODE_EXPLICIT
          compressionQuality = 0.0f
        }

        val typeSpecifier = ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_ARGB)
        val metadata = writer.getDefaultImageMetadata(typeSpecifier, writeParam)
        if (metadata.isReadOnly || !metadata.isStandardMetadataFormatSupported) continue
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
      } finally {
        writer.dispose()
      }
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
