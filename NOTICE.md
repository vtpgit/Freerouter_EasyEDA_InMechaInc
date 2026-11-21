# Credits and licenses

## Routing engine: Freerouting

All routing is done by Freerouting. The bridge passes boards to it and results back.

- **Original author: Alfons Wirtz.** He wrote the router (FreeRouting, freerouting.net).
  His source files carry creation dates from 2002 to 2007.
  In March 2014 he published the complete source under the GNU General Public License version 3.
  His source files carry the notice "Copyright (C) 2014 Alfons Wirtz, website www.freerouting.net".
  Copy of that release: https://github.com/nikropht/FreeRouting
- **Current maintainers: the Freerouting project.** Andras Fuchs maintains it. Michael Hoffer and many other
  contributors have worked on it. Project: https://github.com/freerouting/freerouting

Engine version used here: Freerouting 2.5.0, built 2026-10-05, commit `b647a2f824cfbc54bc4dcaee71b03b7cd945eb76`.
License: GPL-3.0. Source of exactly this version: https://github.com/freerouting/freerouting/tree/v2.5.0

The setup script downloads the unmodified release file `freerouting-2.5.0.jar` from the Freerouting project.
SHA-256: `f6f51bb02245e8e717f9359bd260cc9c5c0b1bc0acc8b7cb2cd5b8ffeb5de3c7`.

This repository and its release files do not contain the engine.

## The bridge

Customization of the router for EasyEDA Standard, feature development, testing, integration testing and productization:

**Valentine Petrov, INMECHA INC**
245 S Spruce Ave
South San Francisco, CA 94080
USA

Copyright (C) 2026 Valentine Petrov, INMECHA INC.

The bridge (`bridge.jar`, source in `src/`, scripts in `easyeda/`) is licensed under the GNU General Public License,
version 3 or any later version. See the file `LICENSE`. It comes without any warranty.

The bridge contains no code of Freerouting and no code of EasyEDA. It starts the engine as a separate program.

Integration tests in the EasyEDA Standard editor were made by Valentine Petrov on boards of his own design.
See `docs/TEST_REPORT.md`.

## Example boards

The board designs in the folder `examples` are by Valentine Petrov, INMECHA INC.
They are not part of the program and are not under its license. See `examples/README.md`.

## Interface to the editor

The bridge answers the local auto router interface of the EasyEDA Standard editor: a websocket on 127.0.0.1, port 3579.
It was written for interoperability with the editor, from the messages that the editor and its local auto router 0.8.11 exchange.
The script and the toolbar button use the documented extension interface of the editor.

EasyEDA is a trademark of its owner. This project is not affiliated with EasyEDA, with JLCPCB or with the Freerouting project.

## Java runtime

If the setup script downloads a Java runtime, it is Eclipse Temurin from the Eclipse Adoptium project.
License: GNU General Public License version 2 with the Classpath Exception. https://adoptium.net
