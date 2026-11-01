package org.rsmod.content.raids.tfa.raid.nexus

import dev.openrune.rscm.RSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.types.ItemServerType
import dev.openrune.types.aconverted.interf.IfButtonOp
import jakarta.inject.Inject
import kotlin.math.min
import org.rsmod.api.invtx.invTransaction
import org.rsmod.api.invtx.select
import org.rsmod.api.market.MarketPrices
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.output.objExamine
import org.rsmod.api.player.vars.intVarBit
import org.rsmod.api.shops.operation.ShopOperations
import org.rsmod.api.shops.restock.ShopRestockProcess
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.Inventory
import org.rsmod.game.shop.Shop
import org.rsmod.game.type.getInvObj

private var Player.supplyCredits by intVarBit("varbit.tfa_supply_points")

/** Supply Credits: earned in the raid, spent at the Quartermaster, gone when the raid ends. */
object TfaSupplyCredits {
    /** tsps estimate: a spectator never saw credits change. */
    const val PER_CHALLENGE = 10 // tunable

    /** `tfa_supply_points` is 7 bits. */
    const val MAX = 127

    fun get(player: Player): Int = player.supplyCredits

    fun add(player: Player, amount: Int) {
        player.supplyCredits = (player.supplyCredits + amount).coerceIn(0, MAX)
    }

    fun reset(player: Player) {
        if (player.supplyCredits != 0) player.supplyCredits = 0
    }
}

/**
 * Alliance Supplies, the Quartermaster's shop: fixed prices in Supply Credits, nothing sold back.
 * Prices are the Wiki's (Quartermaster (The Fractured Archive), cache omnishop rows
 * `tfa_midraid_supplies_shop*`). Stock lives in [SHOP] and [SHOP_FAILURE].
 */
class TfaSupplyShopOperations
@Inject
constructor(
    private val restockProcess: ShopRestockProcess,
    private val marketPrices: MarketPrices,
) : ShopOperations {
    override fun shopInvOp(
        player: Player,
        sideInv: Inventory,
        shop: Shop,
        slot: Int,
        op: IfButtonOp,
    ) {
        when (op) {
            IfButtonOp.Op1 -> value(player, shop, slot)
            IfButtonOp.Op2 -> buy(player, sideInv, shop, slot, request = 1)
            IfButtonOp.Op3 -> buy(player, sideInv, shop, slot, request = 5)
            IfButtonOp.Op4 -> buy(player, sideInv, shop, slot, request = 10)
            IfButtonOp.Op5 -> buy(player, sideInv, shop, slot, request = 50)
            IfButtonOp.Op10 -> examine(player, shop.inv, slot)
            else -> return
        }
    }

    override fun sideInvOp(
        player: Player,
        sideInv: Inventory,
        shop: Shop,
        slot: Int,
        op: IfButtonOp,
    ) {
        when (op) {
            IfButtonOp.Op10 -> examine(player, sideInv, slot)
            else -> player.mes(CANNOT_SELL)
        }
    }

    private fun value(player: Player, shop: Shop, slot: Int) {
        val type = shop.inv[slot]?.let(::getInvObj) ?: return
        val price = PRICES[type.internalName()] ?: return
        val cost =
            if (price == 0) "nothing" else "$price Supply Credit${if (price == 1) "" else "s"}"
        player.mes("${type.name}: currently costs $cost.")
    }

    private fun buy(player: Player, sideInv: Inventory, shop: Shop, slot: Int, request: Int) {
        val shopInv = shop.inv
        val obj = shopInv[slot] ?: return
        val type = getInvObj(obj)
        val price = PRICES[type.internalName()] ?: return
        val stock = min(shopInv.count(obj, type), request)
        if (stock == 0) {
            player.mes("That item is currently out of stock.")
            return
        }
        val credits = TfaSupplyCredits.get(player)
        val affordable = if (price == 0) Int.MAX_VALUE else credits / price
        val space = if (type.isStackable) Int.MAX_VALUE else sideInv.freeSpace()
        val count = min(stock, min(affordable, space))
        if (count <= 0) {
            player.mes(if (affordable <= 0) NOT_ENOUGH else NOT_ENOUGH_SPACE)
            return
        }
        val transaction =
            player.invTransaction(sideInv) {
                val inv = select(sideInv)
                val shopSel = select(shopInv)
                delete {
                    this.from = shopSel
                    this.obj = obj.id
                    this.strictCount = count
                }
                insert {
                    this.into = inv
                    this.obj = obj.id
                    this.strictCount = count
                }
            }
        if (!transaction.success) {
            player.mes(NOT_ENOUGH_SPACE)
            return
        }
        TfaSupplyCredits.add(player, -price * count)
        restockProcess += shopInv
        if (count < stock) player.mes(if (affordable < stock) NOT_ENOUGH else NOT_ENOUGH_SPACE)
    }

    private fun examine(player: Player, inv: Inventory, slot: Int) {
        val obj = inv[slot] ?: return
        val type = getInvObj(obj)
        player.objExamine(type, obj.count, marketPrices[type] ?: 0)
    }

    private fun ItemServerType.internalName(): String =
        runCatching { RSCM.getReverseMapping(RSCMType.OBJ, id) }.getOrDefault("")

    companion object {
        const val CURRENCY = "currency.tfa_supply_credits"
        const val SHOP = "inv.tfa_supplies_shop"
        const val SHOP_FAILURE = "inv.tfa_supplies_shop_failure"

        private const val NOT_ENOUGH = "You don't have enough Supply Credits." // unverified
        private const val NOT_ENOUGH_SPACE = "You don't have enough inventory space."
        private const val CANNOT_SELL = "You can't sell items to this store." // unverified

        private val PRICES: Map<String, Int> =
            mapOf(
                "obj.4dosepotionofsaradomin" to 3,
                "obj.4dose2restore" to 3,
                "obj.4doseprayerrestore" to 2,
                "obj.mantaray" to 2,
                "obj.halibut" to 2,
                "obj.giant_krill" to 1,
                "obj.2doseprayerrestore" to 1,
                "obj.2doserangerspotion" to 2,
                "obj.2dose2combat" to 2,
                "obj.tfa_midraid_supplies_leftovers" to 0,
            )
    }
}
