// Copyright 2017 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
// //////////////////////////////////////////////////////////////////////////////

package signallatexbot.util

object Hex {
  private const val HEX_CHARS = "0123456789abcdef"

  /** Encodes a byte array to hex.  */
  fun encode(bytes: ByteArray): String {
    val result = StringBuilder(2 * bytes.size)
    for (b in bytes) {
      val unsigned = b.toInt() and 0xff
      result.append(HEX_CHARS[unsigned / 16])
      result.append(HEX_CHARS[unsigned % 16])
    }
    return result.toString()
  }

  /** Decodes a hex string to a byte array.  */
  fun decode(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "bad string length" }
    val size = hex.length / 2
    val result = ByteArray(size)
    for (i in 0 until size) {
      val hi = hex[2 * i].digitToIntOrNull(16) ?: error("non-hex input")
      val lo = hex[2 * i + 1].digitToIntOrNull(16) ?: error("non-hex input")
      result[i] = (16 * hi + lo).toByte()
    }
    return result
  }
}
