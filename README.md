Antsy
=====

Antsy is based on the Clojure [ants simulation][ants.clj] by Rich Hickey,
and ported to Scala using [CCSTM][ccstm], [Spde][spde], and [HawtDispatch][hawtdispatch].

This is a variation of the [Ants simulation][ants] that uses Akka.

[ants.clj]: http://clojure.googlegroups.com/web/ants.clj
[ccstm]: http://ppl.stanford.edu/ccstm
[spde]: http://technically.us/spde/
[hawtdispatch]: http://hawtdispatch.fusesource.org
[ants]: http://github.com/pvlugter/ants


Requirements
------------

To build and run Antsy you need [Simple Build Tool][sbt] (sbt).

[sbt]: http://code.google.com/p/simple-build-tool/


Running
-------

First time, sbt update to get dependencies:

    sbt update

To run Antsy use sbt run:

    sbt run


Self-contained jar
------------------

Rather than building from source there is also a self-contained proguarded jar in the Downloads section.

Run with:

    java -jar antsy-0.1.jar


Notice
------

Ants is based on the Clojure ants simulation by Rich Hickey.

Here is the original notice:

Copyright (c) Rich Hickey. All rights reserved.
The use and distribution terms for this software are covered by the
Common Public License 1.0 ([http://opensource.org/licenses/cpl1.0.php][cpl])
which can be found in the file cpl.txt at the root of this distribution.
By using this software in any fashion, you are agreeing to be bound by
the terms of this license.
You must not remove this notice, or any other, from this software.

[cpl]: http://opensource.org/licenses/cpl1.0.php
