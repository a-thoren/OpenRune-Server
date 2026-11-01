package org.rsmod.content.raids.tfa.raid.encounter.ezekiel

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.types.MoveRestrict
import dev.openrune.types.NpcMode
import dev.openrune.types.aconverted.SpotanimType
import org.rsmod.api.bossbar.plugin.BossHpBarScript
import org.rsmod.api.bosses.runtime.Angles
import org.rsmod.api.bosses.runtime.BossDeps
import org.rsmod.api.bosses.runtime.BossExtensionRegistry
import org.rsmod.api.bosses.runtime.adoptOwnedNpc
import org.rsmod.api.bosses.runtime.clearOwnedLocs
import org.rsmod.api.bosses.runtime.runAbility
import org.rsmod.api.bosses.runtime.spawnOwnedLoc
import org.rsmod.api.instances.InstanceSession
import org.rsmod.api.instances.timer.formatInstanceKillTime
import org.rsmod.api.npc.apPlayer2
import org.rsmod.api.npc.heal
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.isValidTarget
import org.rsmod.api.player.midiSong
import org.rsmod.api.player.output.mes
import org.rsmod.api.table.TfaEzekielScalingRow
import org.rsmod.content.raids.tfa.raid.TfaFight
import org.rsmod.content.raids.tfa.raid.TfaRoomCleared
import org.rsmod.content.raids.tfa.raid.encounter.ezekiel.EzekielVarns.DEAD
import org.rsmod.content.raids.tfa.raid.encounter.ezekiel.EzekielVarns.DONE
import org.rsmod.content.raids.tfa.raid.encounter.ezekiel.EzekielVarns.FINALE
import org.rsmod.content.raids.tfa.raid.encounter.ezekiel.EzekielVarns.HP_MAX
import org.rsmod.content.raids.tfa.raid.encounter.ezekiel.EzekielVarns.WEAKENED
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.util.PathingEntityCommon
import org.rsmod.game.headbar.Headbar
import org.rsmod.game.hit.HitType
import org.rsmod.game.interact.InteractionPlayer
import org.rsmod.game.map.Direction
import org.rsmod.game.map.collision.isZoneValid
import org.rsmod.map.CoordGrid

/**
 * One Ezekiel room: Ezekiel, his animated swords and the finale's fence, and the state the fight
 * keeps between ticks. The spec drives his cycle, cleave, magic and star; the swords and the finale
 * barrage live here because they carry per-sword state. Handlers register on a scoped
 * [BossExtensionRegistry] so the shared spec reaches this room only.
 */
internal class EzekielFight(
    override val session: InstanceSession,
    val origin: CoordGrid,
    sharedDeps: BossDeps,
    private val hpBar: BossHpBarScript,
    private val interactions: AiPlayerInteractions,
    private val events: EventBus,
) : TfaFight {
    private val scope = BossExtensionRegistry()
    private val deps = sharedDeps.withExtensions(scope)
    private val registry = sharedDeps.extensionRegistry

    lateinit var boss: Npc
        private set

    private class Sword(val npc: Npc, val placement: SwordPlacement, val start: CoordGrid) {
        var charge = 0
    }

    private val swords = ArrayList<Sword>()
    private val fence = ArrayList<Npc>()
    private val finaleSwords = ArrayList<Pair<Npc, FinaleSword>>()
    private var deathSword: Npc? = null
    private val barOpened = HashSet<Player>()
    private var barClosed = false
    private var started = false
    private var startedAt = 0
    private var endedAt: Int? = null

    override val elapsedTicks: Int?
        get() = if (started) (endedAt ?: deps.mapClock.cycle) - startedAt else null

    override val finished: Boolean
        get() = endedAt != null

    fun contains(tile: CoordGrid): Boolean = EzekielArena.inRoom(origin, tile)

    fun fighters(): List<Player> = EzekielArena.players(origin, deps.playerList)

    fun arenaEntry(): CoordGrid = origin.translate(ARENA_ENTRY_DX, ARENA_ENTRY_DZ)

    fun isSword(npc: Npc): Boolean = swords.any { it.npc === npc }

    private fun active(): Boolean =
        !finished && boss.isSlotAssigned && boss.vars[DEAD] == 0 && boss.vars[FINALE] == 0

    fun open(): List<Npc> {
        scope.register("ezekiel.bar") { refreshBar() }
        scope.register("ezekiel.swords") { raiseSwords() }
        scope.register("ezekiel.finale_clear") { clearSwords() }
        scope.register("ezekiel.finale_setup") { finaleSetup() }
        scope.register("ezekiel.death") { death() }
        scope.register("ezekiel.complete") { complete() }

        val ezekiel = Npc(BOSS, origin)
        ezekiel.respawnDir = Direction.South
        ezekiel.moveRestrict = MoveRestrict.NoMove
        deps.npcRepo.add(ezekiel, Int.MAX_VALUE)
        registry.bind(ezekiel, scope)
        boss = ezekiel
        return listOf(ezekiel)
    }

    fun dispose() {
        registry.unbind(boss)
        val extras =
            swords.map { it.npc } +
                fence +
                finaleSwords.map { it.first } +
                listOfNotNull(deathSword)
        for (npc in extras + boss) {
            if (npc.isSlotAssigned) deps.npcRepo.del(npc, Int.MAX_VALUE)
        }
    }

    fun ready(player: Player) {
        if (started || !boss.isSlotAssigned) return
        started = true
        startedAt = deps.mapClock.cycle
        val size = session.occupants.size.coerceIn(MIN_SCALING, MAX_SCALING)
        boss.vars[HP_MAX] = TfaEzekielScalingRow.getRow("dbrow.tfa_ezekiel_scaling_$size").ezekiel
        boss.moveRestrict = boss.type.moveRestrict
        boss.apRangeOverride = EzekielArena.REACH
        boss.apRequiresLineOfSight = false
        boss.apPlayer2(player, interactions)
        deps.runAbility(boss, player, "begin")
        player.mes("Challenge started: Ezekiel.")
        heartbeat()
    }

    /**
     * His tank is the first player in until they die (Wiki); then the nearest fighter. Spec timers
     * and the attack selector only tick against a valid engaged player.
     */
    private fun heartbeat() {
        deps.worldQueues.add(1) {
            if (finished || !boss.isSlotAssigned) return@add
            val engaged = (boss.interaction as? InteractionPlayer)?.target
            val usable = engaged != null && engaged.isValidTarget() && contains(engaged.coords)
            if (!usable) {
                fighters()
                    .minByOrNull { EzekielArena.footprintDistance(boss.coords, it.coords) }
                    ?.let { boss.apPlayer2(it, interactions) }
            }
            heartbeat()
        }
    }

    fun run(player: Player, ability: String) {
        deps.runAbility(boss, player, ability)
    }

    // ---- the swords ---------------------------------------------------------------------------

    /** Raises a random pattern's swords; each flies [FLY_TICKS] (+ its stagger) later. */
    private fun raiseSwords() {
        if (!active()) return
        val pattern = EzekielArena.patterns { deps.random.of(it) }
        boss.say(pattern.line)
        val raised = pattern.swords.mapNotNull(::spawnSword)
        if (raised.isEmpty()) return
        var flown = 0
        var crossed = false
        for (sword in raised) {
            deps.worldQueues.add(FLY_TICKS + sword.placement.stagger) {
                if (!active() || sword !in swords) return@add
                if (fly(sword)) crossed = true
                if (++flown < raised.size) return@add
                if (crossed) runOnTank("weaken") else healFromMiss()
            }
        }
    }

    private fun spawnSword(placement: SwordPlacement): Sword? {
        val start = EzekielArena.swordStart(origin, placement)
        if (!deps.collision.isZoneValid(start)) return null
        val npc = Npc(SWORD, start)
        npc.respawnDir = facing(placement.side)
        npc.moveRestrict = MoveRestrict.NoMove
        npc.mode = NpcMode.None
        npc.ignoreCombatInteractions = true
        npc.respawns = false
        deps.npcRepo.add(npc, Int.MAX_VALUE)
        deps.adoptOwnedNpc(boss, npc)
        timerBar?.let { bar ->
            npc.showHeadbar(
                Headbar.fromNoSource(bar.first, bar.first, bar.second, 0, 0, TIMER_CYCLES)
            )
        }
        val sword = Sword(npc, placement, start)
        swords += sword
        return sword
    }

    /** Twelve tiles along its lane in one tick; returns whether it went through Ezekiel. */
    private fun fly(sword: Sword): Boolean {
        val npc = sword.npc
        val side = sword.placement.side
        val end = EzekielArena.swordEnd(sword.start, side)
        chargeBar?.let { npc.removeHeadbar(it.first) }
        if (npc.isSlotAssigned && deps.collision.isZoneValid(end)) {
            PathingEntityCommon.exactMove(
                npc,
                npc.coords,
                end,
                delay1 = 0,
                delay2 = FLIGHT_CYCLES,
                dir = Angles.bearing(sword.start, end),
                collision = deps.collision,
            )
            npc.infoProtocol.setTransparency(
                FLIGHT_CYCLES,
                FADE_END_CYCLES,
                0,
                TRANSPARENT,
                useStartTransparency = true,
            )
        }
        val full = EzekielArena.CHARGE_FULL
        val max = EzekielArena.SWORD_MAX * (full + sword.charge.coerceAtMost(full)) / full
        for (player in fighters()) {
            if (!EzekielArena.inLane(sword.start, side, player.coords)) continue
            val damage = deps.random.of(max / 2, max)
            player.queueHit(boss, 1, HitType.Typeless, damage, modifier = deps.playerHitModifier)
        }
        deps.worldQueues.add(SWORD_GONE_TICKS) { removeSword(sword) }
        val crossed = EzekielArena.laneCrosses(sword.start, side, boss.coords)
        if (crossed) runOnTank("sword_strike")
        return crossed
    }

    private fun removeSword(sword: Sword) {
        swords.remove(sword)
        if (sword.npc.isSlotAssigned) deps.npcRepo.del(sword.npc, Int.MAX_VALUE)
    }

    private fun clearSwords() {
        for (sword in swords.toList()) removeSword(sword)
    }

    /** Players' hits on a sword charge it instead of hurting it (capture; Wiki: more damage). */
    fun chargeSword(npc: Npc, damage: Int) {
        val sword = swords.firstOrNull { it.npc === npc } ?: return
        sword.charge += damage
        npc.hitpoints = npc.baseHitpointsLvl
        chargeBar?.let { (id, segments) ->
            val fill = (sword.charge * segments / EzekielArena.CHARGE_FULL).coerceAtMost(segments)
            npc.showHeadbar(Headbar.fromNoSource(id, id, fill, fill, 0, 0))
        }
    }

    /** All the swords missed him: he heals (Wiki: "a significant amount"; 5% here). */
    private fun healFromMiss() {
        if (boss.vars[WEAKENED] != 0) return
        boss.anim(HEAL_SEQ)
        boss.spotanim(HEAL_SPOT)
        boss.heal(
            boss.baseHitpointsLvl * EzekielArena.HEAL_PERCENT / FULL_PERCENT,
            showHitsplat = true,
        )
        updateBar()
    }

    private fun runOnTank(ability: String) {
        val target = (boss.interaction as? InteractionPlayer)?.target ?: fighters().firstOrNull()
        if (target != null) deps.runAbility(boss, target, ability)
    }

    // ---- the finale ---------------------------------------------------------------------------

    /** Pen, blockers and fence; the finale swords two ticks on, the first barrage six after. */
    private fun finaleSetup() {
        if (finished || !boss.isSlotAssigned) return
        pushIn()
        for (tile in EzekielArena.wallTiles(origin)) {
            deps.spawnOwnedLoc(boss, tile, BLOCKER, 0, false)
        }
        deps.worldRepo.soundArea(boss.coords, FENCE_SYNTH, radius = SOUND_RADIUS)
        for ((dx, dz, number) in EzekielArena.FENCE) {
            val npc = spawnProp(FENCE_NPC, origin.translate(dx, dz), Direction.South) ?: continue
            npc.anim("seq.vfx_ezekiel_sword_fence_drop0$number")
            npc.setIdleAnim("seq.vfx_ezekiel_sword_fence_idle0$number")
            fence += npc
        }
        deps.worldQueues.add(FINALE_SWORDS_AFTER) { dropFinaleSwords() }
        deps.worldQueues.add(FIRST_BARRAGE_AFTER) { barrageLoop() }
    }

    /** At the finale everyone is pushed into the pen, unhurt (user's report of live). */
    private fun pushIn() {
        val free = EzekielArena.penTiles(origin).shuffled()
        for ((i, player) in fighters().withIndex()) {
            if (EzekielArena.inPen(origin, player.coords)) continue
            PathingEntityCommon.teleport(player, deps.collision, free[i % free.size])
        }
    }

    private fun dropFinaleSwords() {
        if (finished || !boss.isSlotAssigned || boss.vars[DEAD] != 0) return
        for (def in EzekielArena.FINALE_SWORDS) {
            val npc =
                spawnProp(FINALE_NPC, origin.translate(def.dx, def.dz), Direction.East) ?: continue
            npc.anim("seq.vfx_ezekiel_finale_sword_drop0${def.number}")
            if (def.number != 1) npc.setIdleAnim("seq.vfx_ezekiel_finale_sword_idle0${def.number}")
            deps.worldRepo.soundArea(npc.coords, FINALE_DROP_SYNTH, radius = SOUND_RADIUS)
            finaleSwords += npc to def
        }
    }

    private fun barrageLoop() {
        if (finished || !boss.isSlotAssigned || boss.vars[DEAD] != 0) return
        barrage()
        deps.worldQueues.add(BARRAGE_PERIOD) { barrageLoop() }
    }

    /**
     * Three steps a tick apart: lightning on two of the three finale swords each step. The one left
     * out keeps its row's east pen tile safe; the rest of the pen is struck.
     */
    private fun barrage() {
        if (finaleSwords.isEmpty()) return
        boss.spotanim(FINALE_CAST_SPOT, delay = FINALE_CAST_DELAY)
        deps.worldRepo.soundArea(
            boss.coords,
            FINALE_CAST_SYNTH,
            delay = FINALE_CAST_DELAY,
            radius = SOUND_RADIUS,
        )
        val order = finaleSwords.indices.shuffled()
        for ((step, open) in order.withIndex()) {
            for ((index, entry) in finaleSwords.withIndex()) {
                if (index == open) continue
                val (npc, def) = entry
                npc.spotanim(
                    "spotanim.vfx_ezekiel_sword_telegraph0${def.number}",
                    delay = STEP_CYCLES * step,
                    slot = step,
                )
                deps.worldRepo.soundArea(
                    npc.coords,
                    def.synth,
                    delay = STEP_CYCLES * step,
                    radius = SOUND_RADIUS,
                )
            }
            val safe = origin.translate(EzekielArena.PEN_NE_DX, finaleSwords[open].second.safeRow)
            val struck = EzekielArena.penTiles(origin).filter { it != safe }
            val delay = IMPACT_DELAYS[step]
            for (tile in struck) {
                val spot = if ((tile.x + tile.z) % 2 != 0) IMPACT_MEDIUM else IMPACT_SMALL
                deps.worldRepo.spotanimMap(
                    SpotanimType(spot.asRSCM(RSCMType.SPOTANIM)),
                    tile,
                    delay = delay,
                )
            }
            deps.worldRepo.soundArea(
                boss.coords,
                FENCE_SYNTH,
                delay = IMPACT_SOUND_CYCLES + STEP_CYCLES * step,
                radius = SOUND_RADIUS,
            )
            deps.worldQueues.add(delay / STEP_CYCLES) {
                if (finished || !boss.isSlotAssigned || boss.vars[DEAD] != 0) return@add
                for (player in fighters()) {
                    if (player.coords !in struck) continue
                    val damage = deps.random.of(EzekielArena.BARRAGE_MIN, EzekielArena.BARRAGE_MAX)
                    player.queueHit(
                        boss,
                        1,
                        HitType.Typeless,
                        damage,
                        modifier = deps.playerHitModifier,
                    )
                }
            }
        }
    }

    private fun spawnProp(type: String, tile: CoordGrid, dir: Direction): Npc? {
        if (!deps.collision.isZoneValid(tile)) return null
        val npc = Npc(type, tile)
        npc.respawnDir = dir
        npc.moveRestrict = MoveRestrict.NoMove
        npc.mode = NpcMode.None
        npc.ignoreCombatInteractions = true
        npc.respawns = false
        deps.npcRepo.add(npc, Int.MAX_VALUE)
        deps.adoptOwnedNpc(boss, npc)
        return npc
    }

    // ---- death and the bar --------------------------------------------------------------------

    /**
     * The finale swords' despawns, the death sword left on his tile, the fence fading at the end.
     */
    private fun death() {
        clearSwords()
        for ((npc, def) in finaleSwords) npc.anim(
            "seq.vfx_ezekiel_finale_sword_despawn0${def.number}"
        )
        spawnProp(DEATH_SWORD, boss.coords, Direction.South)?.let {
            it.anim(DEATH_SWORD_SEQ)
            deathSword = it
        }
        deps.worldQueues.add(FENCE_FADE_AFTER) {
            for (npc in fence) {
                if (npc.isSlotAssigned) {
                    npc.infoProtocol.setTransparency(0, STEP_CYCLES, 0, TRANSPARENT, true)
                }
            }
        }
    }

    fun refreshBar() {
        if (barClosed) return
        for (player in fighters()) {
            if (barOpened.add(player)) hpBar.onOpen(player, boss)
            pushHp(player)
        }
    }

    fun updateBar() {
        if (barClosed) return
        for (player in fighters()) pushHp(player)
    }

    private fun pushHp(player: Player) {
        val hp = if (boss.hitpoints <= 1) 0 else boss.hitpoints
        hpBar.onUpdate(player, boss, hp, boss.baseHitpointsLvl)
    }

    private fun closeBar() {
        if (barClosed) return
        barClosed = true
        fighters().forEach(hpBar::onDeathClose)
    }

    fun setHealthPercent(percent: Int): Boolean {
        if (!started || !boss.isSlotAssigned) return false
        boss.hitpoints = maxOf(1, (boss.baseHitpointsLvl.toLong() * percent / FULL_PERCENT).toInt())
        updateBar()
        return true
    }

    fun debugState(): List<String> {
        val encounter = deps.encounterRegistry.of(boss)
        return listOf(
            "[debug] started=$started origin=$origin at=${boss.coords} " +
                "phase=${encounter.currentPhaseName} hp=${boss.hitpoints}/${boss.baseHitpointsLvl}",
            "[debug] weakened=${boss.vars[WEAKENED]} finale=${boss.vars[FINALE]} " +
                "swords=${swords.size} charges=${swords.map { it.charge }}",
        )
    }

    private fun complete() {
        if (finished) return
        val now = deps.mapClock.cycle
        endedAt = now
        boss.vars[DONE] = 1
        for (fighter in fighters()) {
            val precise = fighter.vars["varbit.option_precise_timing"] != 0
            val duration = formatInstanceKillTime(now - startedAt, precise)
            fighter.mes("Challenge complete: Ezekiel. Duration: <col=ef1020>$duration</col>")
        }
        closeBar()
        for (npc in fence + finaleSwords.map { it.first }) {
            if (npc.isSlotAssigned) deps.npcRepo.del(npc, Int.MAX_VALUE)
        }
        fence.clear()
        finaleSwords.clear()
        // The 9x9 is walkable again once the swords are gone.
        deps.clearOwnedLocs(boss)
        playVictorySong()
        events.publish(TfaRoomCleared(session.id, "ezekiel"))
    }

    private fun playVictorySong() {
        if (!boss.isSlotAssigned) return
        for (fighter in fighters()) fighter.midiSong(VICTORY_SONG, fadeOutSpeed = VICTORY_FADE_OUT)
        deps.worldQueues.add(1) { playVictorySong() }
    }

    private fun facing(side: SwordSide): Direction =
        when (side) {
            SwordSide.NORTH -> Direction.South
            SwordSide.SOUTH -> Direction.North
            SwordSide.WEST -> Direction.East
            SwordSide.EAST -> Direction.West
        }

    private companion object {
        const val BOSS = "npc.tfa_ezekiel"
        const val SWORD = "npc.tfa_ezekiel_sword"
        const val FENCE_NPC = "npc.tfa_ezekiel_sword_noop"
        const val FINALE_NPC = "npc.tfa_ezekiel_finale_vfx"
        const val DEATH_SWORD = "npc.tfa_ezekiel_death_sword"
        const val DEATH_SWORD_SEQ = "seq.npc_ezekiel_death_sword01"
        const val BLOCKER = "loc.invisible_type8_blocking"
        const val HEAL_SEQ = "seq.npc_ezekiel_heal01"
        const val HEAL_SPOT = "spotanim.ezekiel_heal_spotanim_vfx01"
        const val FINALE_CAST_SPOT = "spotanim.vfx_ezekiel_finale_cast01"
        const val IMPACT_MEDIUM = "spotanim.vfx_ezekiel_finale_impact_medium01"
        const val IMPACT_SMALL = "spotanim.vfx_ezekiel_finale_impact_small01"
        const val FENCE_SYNTH = "synth.tfa_ezekiel_fence_impact"
        const val FINALE_CAST_SYNTH = "synth.tfa_ezekiel_finale_cast_sound"
        const val FINALE_DROP_SYNTH = "synth.tfa_ezekiel_finale_drop"
        const val TIMER_BAR = "headbar.tfa_ezekiel_sword_timer"
        const val CHARGE_BAR = "headbar.charge_70"

        const val MIN_SCALING = 2
        const val MAX_SCALING = 8
        const val FULL_PERCENT = 100
        const val ARENA_ENTRY_DX = 4
        const val ARENA_ENTRY_DZ = -6
        const val SOUND_RADIUS = 25
        const val VICTORY_SONG = 147
        const val VICTORY_FADE_OUT = 50

        const val FLY_TICKS = 14
        const val FLIGHT_CYCLES = 30
        const val FADE_END_CYCLES = 60
        const val TRANSPARENT = 255
        const val TIMER_CYCLES = 390
        const val SWORD_GONE_TICKS = 2
        const val FINALE_SWORDS_AFTER = 2
        const val FIRST_BARRAGE_AFTER = 6
        const val BARRAGE_PERIOD = 11
        const val STEP_CYCLES = 30
        const val FINALE_CAST_DELAY = 225
        const val IMPACT_SOUND_CYCLES = 120
        const val FENCE_FADE_AFTER = 9
        val IMPACT_DELAYS = intArrayOf(124, 154, 184)

        /**
         * Resolved on first use, never in static init (silent failure 1); absent bars are skipped.
         */
        val timerBar: Pair<Int, Int>? by lazy { headbar(TIMER_BAR) }
        val chargeBar: Pair<Int, Int>? by lazy { headbar(CHARGE_BAR) }

        private fun headbar(name: String): Pair<Int, Int>? =
            runCatching { ServerCacheManager.getHealthBar(name.asRSCM(RSCMType.HEADBAR)) }
                .getOrNull()
                ?.let { it.id to it.segments }
    }
}
