# Third-Party Notices

## Better Mob Combat: Reimagined

The `com.fiercerbattles.mobcombat` implementation is adapted from
`Bandit-bytes/Better-Mob-Combat-Reimagined`, master commit
`4731b2ac98fb66c88f67ecbcd229b6077c7ce2cf`, under the MIT License.
Its license text is included at `META-INF/LICENSE-better-mob-combat-reimagined.txt`.

## Mob Player Animator — embedded/adapted implementation

Parts of the humanoid-mob animation bridge are embedded and adapted from **Mob Player Animator**:

- Original project: `Thelnfamous1/Mob-Player-Animator`
- NeoForge 1.21.1 branch referenced for this port: `Tfarcenim/Mob-Player-Animator`, branch `1.21.1`
- Original purpose: extend Player Animator support to humanoid mobs
- License on the referenced GitHub branch: **CC0 1.0 Universal**

The adapted portions include the mob animation stack/model handoff, bend-aware humanoid and illager
rendering, model subclass protections, armor propagation, optional Entity Model Features animation
pause/resume behavior, and the default Fresh Animations vindicator model-part corrections.

The full CC0 legal text is included at:

`META-INF/licenses/Mob-Player-Animator-CC0-1.0.txt`

The embedded code is now in the `com.fiercerbattles.mobcombat.internal.mobanim` and
`com.fiercerbattles.mobcombat.mixin.client.embedded` namespaces. Credit is preserved even though
CC0 does not require attribution.

## Player Animator

This mod still depends on Player Animator at runtime. Player Animator is not bundled into this
source tree and retains its own license and copyright.

## Better Combat — embedded

The `com.fiercerbattles.bettercombat` package, together with `assets/bettercombat` and
`data/bettercombat`, is an embedded copy of **Better Combat** 2.4.0+1.21.1 by Zsolt Molnár
(Daedelus), with credits to Malfu, KosmX and Gecko. The code was converted from Yarn to Mojang
mappings and moved from `net.bettercombat` into this namespace. Its Architectury platform stubs were
replaced with direct NeoForge calls, and its entry point now runs from the Fiercer Battles mod.

Better Combat is licensed **All Rights Reserved**:

```
All Rights Reserved

Copyright (c) 2022 Zsolt Molnár

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```

The embedded copy is for private use. Do not redistribute a build containing it without the
author's permission.

## Tiny Config

`META-INF/jarjar/tiny-config-3.1.0-neoforge.jar` is the unmodified Tiny Config library, bundled the
same way Better Combat bundles it. It retains its own license.

## Cloth Config

Cloth Config remains a separate runtime dependency. It is not bundled and retains its own license.

## Entity Model Features / Entity Texture Features / Fresh Animations

Entity Model Features, Entity Texture Features, and Fresh Animations are not bundled and are not
required dependencies. The optional compatibility bridge calls EMF's public animation API
reflectively when EMF is installed. ETF is included only in the development runtime because it is a
required dependency of EMF; no ETF code is copied or linked into this project.
