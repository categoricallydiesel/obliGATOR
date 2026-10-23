# ObliGATOR

Turns the Obligator's spiked mace head into Zebak's head.

- **Wielded:** the ball on the end of the chain is replaced by a Zebak head hanging from the chain by its neck. This
  applies to every player wielding a charged Obligator, while standing, moving and attacking.
- **5-tick throw:** with a charged Obligator, the thrown mace is redrawn with a Zebak head in place of the ball, which
  grows out of the throw as the ball would.
- **Chomp:** as the throw reaches the furthest point, where the hit lands, a large translucent green Zebak head appears
  along the thrust and snaps its jaws, then fades away.
- The 3-tick attack style only shows the wielded head, without a chomp.

The chomp can be turned off in the plugin's settings.

All changes are cosmetic and only visible to you. Models and animations are loaded from the game cache, so the plugin
does not include any game assets.

## How it works

The thrust is an actor spotanim, which the client merges into the player's model when drawing, so its ball cannot be
hidden in place. The plugin removes the spotanim on your client and redraws it as a `RuneLiteObject` from the same
model and animation, with the ball's faces hidden. The wielded ball is hidden on the player's cached appearance model,
and its original colours are restored when the player stops wielding the Obligator or the plugin is turned off.

RuneLite objects can only be rotated about the vertical axis, so the Zebak heads are posed by writing their vertices each
frame: scaled, rotated to follow the chain, and animated with Zebak's bite.

## Developer mode

When RuneLite is run with `--developer-mode`, typing `::oblig` makes your character wield an Obligator on your client
only, so the plugin can be tested without owning one. `::oblig attack` and `::oblig multi` play the 3-tick and 5-tick
attacks, and further `::oblig` commands tune the head and the chomp live. Run `::oblig help` to list them.
