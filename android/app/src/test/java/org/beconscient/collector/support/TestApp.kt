package org.beconscient.collector.support

import android.app.Application

/** Does nothing on start, unlike CollectorApp, so each test wires what it needs. */
class TestApp : Application()
