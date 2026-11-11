package dev.pocketpaas.data.local

/** Room 5 custom-hostname migration, separate from each site's internal path identity. */
object CustomHostnameSchema {
    val statements: List<String> = listOf(
        "ALTER TABLE `sites` ADD COLUMN `customHostname` TEXT",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_sites_customHostname` ON `sites` (`customHostname`)",
    )
}
