package org.rsmod.content.raids.tfa.raid

import org.rsmod.map.CoordGrid

/**
 * The Archive's rooms, as real-map tiles that each room's instance resolves: where the party
 * arrives, the corner the dead watch from, the tile their camera looks at, where the Alliance
 * Navigator steps in after a win, and the Nexus door (section and side) that leads there. All from
 * tsps' captures (`RaidState.FracturedArchive.js` `ROOMS`, `docs/fractured-archive.md`), with
 * arrivals matching the port's own room entrances.
 *
 * [built] is false for rooms not ported yet; their Nexus doors say they are sealed.
 */
internal enum class TfaRoom(
    val key: String,
    val built: Boolean,
    val arrival: CoordGrid?,
    val spectate: CoordGrid? = null,
    val camera: CoordGrid? = null,
    val navigator: CoordGrid? = null,
    val section: Int? = null,
    val side: TfaSide? = null,
) {
    Guardian(
        key = "guardian",
        built = true,
        arrival = CoordGrid(4127, 5571, 0),
        spectate = CoordGrid(4097, 5569, 0),
        camera = CoordGrid(4127, 5599, 0),
    ),
    Nexus(key = "nexus", built = true, arrival = CoordGrid(4127, 5590, 2)),
    Bork(
        key = "bork",
        built = true,
        arrival = CoordGrid(4173, 5599, 0),
        spectate = CoordGrid(4161, 5569, 0),
        camera = CoordGrid(4191, 5599, 0),
        navigator = CoordGrid(4184, 5599, 0),
        section = 1,
        side = TfaSide.East,
    ),
    Ezekiel(
        key = "ezekiel",
        built = true,
        arrival = CoordGrid(4079, 5536, 0),
        spectate = CoordGrid(4033, 5505, 0),
        camera = CoordGrid(4063, 5535, 0),
        navigator = CoordGrid(4067, 5535, 0),
        section = 1,
        side = TfaSide.West,
    ),
    Dreadnoth(
        key = "dreadnoth",
        built = true,
        arrival = CoordGrid(4174, 5664, 0),
        spectate = CoordGrid(4161, 5633, 0),
        camera = CoordGrid(4191, 5663, 0),
        navigator = CoordGrid(4184, 5663, 0),
        section = 2,
        side = TfaSide.East,
    ),
    Moia(key = "moia", built = false, arrival = null, section = 2, side = TfaSide.West),
    Xarth(
        key = "xarth",
        built = true,
        arrival = CoordGrid(4175, 5535, 0),
        spectate = CoordGrid(4161, 5505, 0),
        camera = CoordGrid(4191, 5535, 0),
        navigator = CoordGrid(4184, 5535, 0),
        section = 3,
        side = TfaSide.East,
    ),
    Dagonhai(key = "dagonhai", built = false, arrival = null, section = 3, side = TfaSide.West),
    Ocellus(
        key = "ocellus",
        built = true,
        arrival = CoordGrid(4128, 5658, 1),
        spectate = CoordGrid(4097, 5633, 1),
        camera = CoordGrid(4127, 5673, 1),
    );

    companion object {
        fun byKey(key: String): TfaRoom? = entries.firstOrNull { it.key == key }

        fun behind(section: Int, side: TfaSide): TfaRoom =
            entries.first { it.section == section && it.side == side }
    }
}
