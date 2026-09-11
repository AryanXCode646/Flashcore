package com.ashishsinghbora.flashcore.flasher.ventoy

/**
 * Third-Party Licensing & Attribution Notice for Ventoy.
 *
 * Ventoy is an open-source multi-boot tool developed by longpanda.
 * License: GNU General Public License v3.0 (GPL-3.0).
 *
 * Flashcore complies with the terms of GPL-3.0:
 * 1. Flashcore itself is licensed under GPL-3.0.
 * 2. Full attribution is provided to the original author (longpanda).
 * 3. Ventoy upstream source code is publicly accessible at https://github.com/ventoy/Ventoy.
 * 4. Trademark Disclaimer: Flashcore is an independent third-party implementation
 *    and is not affiliated with, endorsed by, or sponsored by longpanda or the Ventoy project.
 */
object VentoyLicenseNotice {
    const val UPSTREAM_PROJECT = "Ventoy"
    const val UPSTREAM_AUTHOR = "longpanda <admin@ventoy.net>"
    const val UPSTREAM_URL = "https://github.com/ventoy/Ventoy"
    const val UPSTREAM_LICENSE = "GNU General Public License v3.0 (GPL-3.0)"
    const val GRUB2_LICENSE = "GNU General Public License v3.0 (GPL-3.0) by Free Software Foundation"

    const val DISCLAIMER = "Flashcore is an independent open-source client implementing the " +
            "Ventoy partition specification and asset management. It is not affiliated with, " +
            "endorsed by, or sponsored by longpanda or the Ventoy project."

    fun getFullNotice(): String {
        return buildString {
            appendLine("==================================================")
            appendLine("THIRD-PARTY LICENSING NOTICE: VENTOY")
            appendLine("==================================================")
            appendLine("Project:    $UPSTREAM_PROJECT")
            appendLine("Author:     $UPSTREAM_AUTHOR")
            appendLine("Repository: $UPSTREAM_URL")
            appendLine("License:    $UPSTREAM_LICENSE")
            appendLine("Components: GRUB2 ($GRUB2_LICENSE)")
            appendLine("--------------------------------------------------")
            appendLine(DISCLAIMER)
            appendLine("==================================================")
        }
    }
}
