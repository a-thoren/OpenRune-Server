package org.rsmod.content.slayer.requirements

import jakarta.inject.Inject
import org.rsmod.api.area.checker.AreaChecker
import org.rsmod.api.config.SlayerTaskCredit
import org.rsmod.api.death.NpcAttackValidateHook
import org.rsmod.api.death.NpcAttackValidateResult
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player

internal class SlayerTaskLockAttackHook @Inject constructor(private val areas: AreaChecker) :
    NpcAttackValidateHook {
    override fun validate(player: Player, npc: Npc): NpcAttackValidateResult {
        if (!areas.inArea(KRAKEN_COVE, npc.coords)) return NpcAttackValidateResult.Pass
        if (TENTACLE_MARKER in npc.visType.internalName) return NpcAttackValidateResult.Pass
        if (SlayerTaskCredit.counts(npc.visType, SlayerTaskCredit.taskKey(player))) {
            return NpcAttackValidateResult.Pass
        }
        return NpcAttackValidateResult.Deny(messageFor(npc))
    }

    private fun messageFor(npc: Npc): String =
        if (npc.visType.internalName.startsWith(KRAKEN_PREFIX)) KRAKEN_MESSAGE else FIEND_MESSAGE

    private companion object {
        const val KRAKEN_COVE = "area.kraken_cove"
        const val KRAKEN_PREFIX = "npc.slayer_kraken"
        const val TENTACLE_MARKER = "_tentacle"
        const val KRAKEN_MESSAGE = "Oi, I'm Lieve McCracken and I say leave m' Kraken alone!"
        const val FIEND_MESSAGE = "Oi, I'm Lieve, and I say leave it!"
    }
}
