package org.rsmod.content.generic.killcount

import jakarta.inject.Inject
import org.rsmod.api.config.refs.BaseParams
import org.rsmod.api.death.NpcDeathKillContext
import org.rsmod.api.death.NpcDeathKillHook
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.plugin.module.PluginModule

public class KillcountModule : PluginModule() {
    override fun bind() {
        addSetBinding<NpcDeathKillHook>(KillcountNpcKillHook::class.java)
    }
}

/**
 * Increments the player's killcount varp for the killed npc.
 */
public class KillcountNpcKillHook @Inject constructor() : NpcDeathKillHook {
    override fun onKill(context: NpcDeathKillContext) {
        val awakened = context.npc.vars["varn.awakened_state"] == 1
        val awakenedVarp = if (awakened) context.npc.paramOrNull(BaseParams.killcount_varp_awakened) else null
        val varp = awakenedVarp ?: context.npc.paramOrNull(BaseParams.killcount_varp) ?: return
        val notify = context.npc.paramOrNull(BaseParams.killcount_notify) ?: true
        val count = context.hero.vars[varp] + 1
        VarPlayerIntMapSetter.set(context.hero, varp, count)
        if (notify) {
            val name = if (awakened) "Awakened ${context.npc.name}" else context.npc.name
            context.hero.mes("Your $name kill count is: <col=ff0000>$count</col>")
        }
    }
}
