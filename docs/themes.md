# Themes

Every theme has a Theme entry among its settings. It opens a list of every theme with the
current one marked and focused: up and down move, OK applies, Back closes
without a change. The choice is saved on the TV.

## Dapple TV

The default: tabs, backdrop, favorites row, described under
["Using it"](../README.md#using-it) in the README. The focused card has a white
border and glow; a focused favorite grows a little.

## Terminal

- **Look**: near-black screen, one phosphor green, a monospace font, no
  images, and a faint scanline overlay. A first line holding only the
  clock at the right, a rule of dashes, then one list:
  FAVORITES, APPS and SETTINGS, one uppercase name per line. The focused
  line is a block cursor: inverted, with a "> " prompt and a blinking
  underscore.
- **Keys**: up and down move the cursor and scroll the list under the fixed
  header; left and right do nothing; OK opens; holding OK opens a text menu
  with the same favorites and hide actions; Back jumps to the top.

## Catppuccin

The Terminal theme in [Catppuccin Mocha](https://catppuccin.com/palette)'s
colors and JetBrains Mono (`licenses/JetBrainsMono-OFL.txt`), on the base
color with hints in overlay 1 and no scanlines. Layout and keys are
Terminal's.

- **Sections**: FAVORITES in pink, APPS in blue, SETTINGS in green; the block
  cursor takes the color of the section it is in.
- **Names**, colored by kind like `ls --color`: built-in apps blue,
  sideloaded apps green, settings teal.
- **Menu**: ADD TO (or REMOVE FROM) FAVORITES in yellow, HIDE APP in peach,
  UNINSTALL in red; the focused action is a block of its color. Borders and
  other menu lines are mauve.
- **Clock, footer and rules**: the clock is peach; the footer's keys (OK,
  HOLD OK, BACK) are yellow; the dashed rules are in surface 1, so they
  recede.

## Mintendo Snitch

A flat background, Dark (the default) or a mid-gray Light (its own setting,
the "Look" button), one row of square tiles with a thick cyan focus border
and a 150 ms scale bounce, the focused name large under the row, and the
settings as small round buttons under that, reached by pressing down. A
button's full name shows in the name slot while it is focused.

## Game Queen

The Press Start 2P pixel font (`licenses/PressStart2P-OFL.txt`) and an
original Game Boy screen's colors.

- **Layout**: a 6-wide grid of square tiles across the whole safe area, each
  carrying its app's name in capitals, centred and wrapped to at most two
  lines (cut there, no ellipsis); no icons. A 3 dp border on the focused
  tile and no animation anywhere, the focused name under the grid, and an
  A/B hint line; the grid scrolls so the focused tile always sits fully
  above those two lines. The settings entries, Hidden apps, Theme and Look
  are tiles at the end of the grid, under FAVORITES and APPS sections.
- **Dark** (the default, "Pocket black"): a true black screen, tiles and
  menu panels in the darkest green, text, hints and the focused border in
  the mid green.
- **Light**: the original screen unlit, a light gray with a green tint,
  slightly darker tiles and dark olive ink. The "LOOK" tile switches
  between them.

## Ambient clock

The backdrop photo (or the gradient) and a large clock with the date,
nothing else. Any d-pad press fades the favorites row in at the bottom
(200 ms, alpha only) and focuses it; Down from the row opens every app and
the settings as a grid over a darkened backdrop; 8 s without input and the
clock is alone again.

## Editorial

Warm paper, Dark (the default) or a toned newsprint Light (the last word
of the settings line), the focused app's name set large in Playfair Display
italic (`licenses/PlayfairDisplay-OFL.txt`) with its tagline, the clock small
top right, a row of small tiles bottom right with a thin underline for
focus, and a short settings line bottom left (Settings · Hidden apps ·
Theme · Light) reached by pressing down.
