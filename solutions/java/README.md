# Running Java Demos

Use the launcher instead of editing `pom.xml` for each solution.

## One-click launcher on macOS

Double-click `run-demo.command`. It opens a small picker where you can search for a demo and run it.

## Terminal

```bash
./run-demo.sh --list
./run-demo.sh parkinglot
./run-demo.sh --demo vendingmachine
```

With Maven installed, you can also run from this directory:

```bash
mvn compile exec:java -Dexec.args="parkinglot"
```

The launcher discovers demo classes under `src`, compiles only the selected demo and the classes it needs, then invokes either `main(String[] args)` or `run()`. Unrelated solutions no longer require POM changes and should not block the demo you are running.
