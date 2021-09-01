package signallatexbot.serialization

import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.JsonKeysetReader
import com.google.crypto.tink.JsonKeysetWriter
import com.google.crypto.tink.KeysetHandle
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.GeneralSecurityException

class KeysetHandlePlaintextJsonSerializer : KSerializer<KeysetHandle> {
    override val descriptor: SerialDescriptor
        get() = PrimitiveSerialDescriptor("com.google.crypto.tink.KeysetHandle", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: KeysetHandle) {
        encoder.encodeString(serializeKeysetHandle(value))
    }

    override fun deserialize(decoder: Decoder): KeysetHandle {
        return try {
            CleartextKeysetHandle.read(JsonKeysetReader.withString(decoder.decodeString()))
        } catch (e: IOException) {
            throw SerializationException(e)
        } catch (e: GeneralSecurityException) {
            throw SerializationException(e)
        }
    }

    companion object {
        fun serializeKeysetHandle(keysetHandle: KeysetHandle): String {
            val bos = ByteArrayOutputStream()
            val writer = JsonKeysetWriter.withOutputStream(bos)
            CleartextKeysetHandle.write(keysetHandle, writer)
            return bos.toString(Charsets.UTF_8)
        }
    }
}