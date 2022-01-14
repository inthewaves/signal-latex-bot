package signallatexbot.util

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipalNotFoundException

/**
 * @throws UserPrincipalNotFoundException
 */
fun File.changePosixGroup(newGroupName: String) {
  val lookupService = FileSystems.getDefault().userPrincipalLookupService
  val unixGroupPrincipal = lookupService.lookupPrincipalByGroupName(newGroupName)
    ?: throw UserPrincipalNotFoundException("unable to find POSIX group $newGroupName")
  val fileAttributesView = Files.getFileAttributeView(this.toPath(), PosixFileAttributeView::class.java)
  val currentGroupForDir = fileAttributesView.readAttributes().group()
  if (currentGroupForDir != unixGroupPrincipal) {
    fileAttributesView.setGroup(unixGroupPrincipal)
  }
}

/**
 * Adds the new [permissions] in addition to the original permissons
 */
fun File.addPosixPermissions(vararg permissions: PosixFilePermission) {
  val fileAttrView = Files.getFileAttributeView(this.toPath(), PosixFileAttributeView::class.java)
  val oldPerms = fileAttrView.readAttributes().permissions()
  val newPerms = fileAttrView.readAttributes().permissions().apply { addAll(permissions) }
  if (oldPerms != newPerms) {
    fileAttrView.setPermissions(newPerms)
  }
}

/**
 * Overwrites the existing permissions with the new [permissions]
 *
 * @throws IllegalArgumentException
 * @throws java.io.IOException
 */
fun File.setPosixPermissions(octalPermissions: String) {
  val newPermissions = PosixFilePermissions.fromString(octalPermissions)
  val fileAttrView = Files.getFileAttributeView(this.toPath(), PosixFileAttributeView::class.java)
  val currentPerms = fileAttrView.readAttributes().permissions()
  if (currentPerms != newPermissions) {
    Files.setPosixFilePermissions(toPath(), newPermissions)
  }
}
