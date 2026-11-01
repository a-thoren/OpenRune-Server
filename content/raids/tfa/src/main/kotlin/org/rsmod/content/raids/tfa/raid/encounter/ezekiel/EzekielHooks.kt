package org.rsmod.content.raids.tfa.raid.encounter.ezekiel

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import org.rsmod.api.death.NpcAttackValidateHook
import org.rsmod.api.death.NpcAttackValidateResult
import org.rsmod.api.npc.hit.NpcHitpointsFloorHook
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.Hit

internal val EZEKIEL_ID by lazy { "npc.tfa_ezekiel".asRSCM(RSCMType.NPC) }

internal val EZEKIEL_SWORD_ID by lazy { "npc.tfa_ezekiel_sword".asRSCM(RSCMType.NPC) }

/** Keeps Ezekiel and his swords at 1 hp so the spec, not the hit, decides when he dies. */
internal class EzekielHpFloor : NpcHitpointsFloorHook {
    override fun floor(npc: Npc, hit: Hit): Int =
        if (npc.visType.id == EZEKIEL_ID || npc.visType.id == EZEKIEL_SWORD_ID) 1 else 0
}

/** Ezekiel is retired once he is dead; the spec sets [Npc.ignoreCombatInteractions] then. */
internal class EzekielAttackHook : NpcAttackValidateHook {
    override fun validate(player: Player, npc: Npc): NpcAttackValidateResult =
        if (npc.visType.id == EZEKIEL_ID && npc.ignoreCombatInteractions) {
            NpcAttackValidateResult.Deny("It's already dead.")
        } else {
            NpcAttackValidateResult.Pass
        }
}
