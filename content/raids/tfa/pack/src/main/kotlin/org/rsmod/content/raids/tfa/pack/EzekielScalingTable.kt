package org.rsmod.content.raids.tfa.pack

import dev.openrune.definition.dbtables.dbTable
import dev.openrune.definition.util.VarType

object EzekielScalingTable {

    const val EZEKIEL = 0

    // 5 players is captured (6,870); 2-4 follow Dreadnoth's ratios from the cache's 3,000 (tsps).
    // 6-8 extend the 4->5 step of 1,260 per player (unverified).
    private val ROWS =
        mapOf(2 to 3000, 3 to 4360, 4 to 5610, 5 to 6870, 6 to 8130, 7 to 9390, 8 to 10650)

    fun scaling() =
        dbTable("dbtable.tfa_ezekiel_scaling", serverOnly = true) {
            column("ezekiel", EZEKIEL, VarType.INT)
            for ((players, hp) in ROWS) {
                row("dbrow.tfa_ezekiel_scaling_$players") { column(EZEKIEL, hp) }
            }
        }
}
