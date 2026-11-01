package org.rsmod.content.raids.tfa.pack

import dev.openrune.definition.dbtables.DBTable
import dev.openrune.pack.PluginPack

class TfaPluginPack : PluginPack() {
    override fun dbTables(): List<DBTable> =
        listOf(
            GuardianScalingTable.scaling(),
            DreadnothScalingTable.scaling(),
            BorkScalingTable.scaling(),
            XarthScalingTable.scaling(),
            EzekielScalingTable.scaling(),
        )
}
