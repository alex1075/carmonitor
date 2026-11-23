package uk.co.ashunt.carmonitor.core

import java.io.File

/**
 * Dependency-free test harness (run with `gradle :core:selfTest`).
 * FakeCx5 simulates an ELM327 on a 2.2 SkyActiv-D PCM with header-on CAN output.
 */
class FakeCx5 : ElmLink {
    var rpm = 0.0
    var speed = 0.0
    var egtPostDpf = 300.0
    var alive = true
    val sent = mutableListOf<String>()
    private var hdr = "7E0"
    private var pending = ""
    private var pendingOnce = mutableSetOf("22042B")   // first 22042B answers 7F 22 78

    /** request -> positive payload (without the echo) */
    private fun positive(req: String): List<Int>? = when (req) {
        "0100" -> listOf(0x98, 0x3B, 0x80, 0x13)        // 01,04,05,0B,0C,0D,0F,10,11,1C,20 supported
        "0120" -> listOf(0x80, 0x01, 0x00, 0x01)        // 21, 30(no), 2F? -> keep simple, 40 supported
        "0140" -> listOf(0x40, 0x00, 0x00, 0x00)        // 42 (VPWR)
        "010C" -> (rpm * 4).toInt().let { listOf(it shr 8, it and 0xFF) }
        "010D" -> listOf(speed.toInt())
        "0105" -> listOf(90 + 40)
        "0142" -> listOf(0x36, 0xB0)                    // 14.0 V
        "0902" -> listOf(0x01) + "JMZXXXXXX00123456".map { it.code }
        "03" -> listOf(0x02, 0x04, 0x01, 0x24, 0xA5)    // P0401, P24A5
        "07" -> listOf(0x00)
        "22042B" -> listOf(0x01, 0x3F)                  // 3.19 with /100
        "2203CB" -> ((egtPostDpf + 40) * 10).toInt().let { listOf(it shr 8, it and 0xFF) }
        "2203F4" -> listOf(0x40)                         // EGT1 = 320 C
        "220435" -> listOf(0x04)                         // oil dilution events
        "220C42" -> listOf(0xBE, 0xEF)                   // hidden DID for the sweep
        else -> null
    }

    private fun frames(id: String, payload: List<Int>): List<String> {
        val out = mutableListOf<String>()
        if (payload.size <= 7) {
            out += id + "%02X".format(payload.size) + payload.joinToString("") { "%02X".format(it) } + "00".repeat(7 - payload.size)
        } else {
            out += id + "%02X%02X".format(0x10 or (payload.size shr 8), payload.size and 0xFF) +
                payload.take(6).joinToString("") { "%02X".format(it) }
            var rest = payload.drop(6); var seq = 1
            while (rest.isNotEmpty()) {
                val chunk = rest.take(7); rest = rest.drop(7)
                out += id + "%02X".format(0x20 or (seq and 0xF)) + chunk.joinToString("") { "%02X".format(it) } + "00".repeat(7 - chunk.size)
                seq++
            }
        }
        return out
    }

    override fun send(line: String) {
        if (!alive) throw java.io.IOException("link lost")
        val c = line.trim().uppercase()
        sent += c
        pending = when {
            c == "ATZ" -> "\r\rELM327 v1.5\r\r"
            c.startsWith("ATSH") -> { hdr = c.substring(4); "OK\r\r" }
            c == "ATRV" -> "14.1V\r\r"
            c.startsWith("AT") -> "OK\r\r"
            else -> {
                val rx = Elm327.defaultRx(hdr)!!
                val svc = c.substring(0, 2).toInt(16)
                if (hdr != "7E0") "NO DATA\r\r"
                else if (c in pendingOnce) { pendingOnce.remove(c); frames(rx, listOf(0x7F, svc, 0x78)).joinToString("\r") + "\r\r" }
                else {
                    val p = positive(c)
                    if (p != null) {
                        val echo = c.hexToBytes().map { it.toInt() and 0xFF }.toMutableList(); echo[0] = svc + 0x40
                        frames(rx, echo + p).joinToString("\r") + "\r\r"
                    } else if (svc == 0x22) frames(rx, listOf(0x7F, 0x22, 0x31)).joinToString("\r") + "\r\r"
                    else "NO DATA\r\r"
                }
            }
        }
    }

    override fun readUntilPrompt(timeoutMs: Long): String { val p = pending; pending = ""; return p }
    override fun close() {}
}

object SelfTest {
    private var passed = 0
    private val failures = mutableListOf<String>()
    private fun check(cond: Boolean, msg: String) { if (cond) passed++ else failures += msg }
    private fun eq(a: Any?, b: Any?, msg: String) = check(a == b, "$msg: expected <$b> got <$a>")

    private fun assets(): File {
        val candidates = listOf(File("app/src/main/assets"), File("../app/src/main/assets"))
        return candidates.first { it.isDirectory }
    }

    fun library(): SignalSet {
        val a = assets()
        return SignalSet.merge(
            "cx5-diesel",
            SignalSet.parse("SAEJ1979", File(a, "obdb/SAEJ1979.json").readText()),
            SignalSet.parse("Mazda-CX-5", File(a, "obdb/Mazda-CX-5.json").readText()),
            SignalSet.parse("overlay", File(a, "overlay/Mazda-CX-5.json").readText()),
        )
    }

    fun guard() {
        for (ok in listOf("010C", "01 0d", "03", "07", "0902", "22042B", "ATZ", "ATSH7E0", "ATCRA7E8", "ATSP6", "ATH1", "ATST32"))
            check(RequestGuard.isAllowed(ok), "should allow $ok")
        for (bad in listOf("04", "14FFFFFF", "2E042B01", "31010203", "1003", "1002", "1101", "2701", "2F030001",
                           "3E00", "8502", "34", "22042B00", "2204", "0C", "ATMA", "ATPP0CSV01", "ATBRD08",
                           "ATSH18DA10F1", "010C\r04", "ZZ", "", "AT", "22042B\r2E"))
            check(!RequestGuard.isAllowed(bad), "should refuse '$bad'")
        val elm = Elm327(FakeCx5())
        var threw = false
        try { elm.request("2E042B01") } catch (_: RequestGuard.Refused) { threw = true }
        check(threw, "Elm327.request must refuse a write")
        threw = false
        try { elm.request("31010203") } catch (_: RequestGuard.Refused) { threw = true }
        check(threw, "Elm327.request must refuse a routine")
    }

    fun parsing() {
        val p = Elm327.parse("010C", "7E804410C1AF8000000\r\r", "7E8")
        check(p is ObdResponse.Positive && p.hex() == "1AF8", "SF positive: $p")
        val n = Elm327.parse("22FFFF", "7E8037F2231000000\r", "7E8")
        eq(n, ObdResponse.Negative(0x22, 0x31), "NRC 31")
        val pend = Elm327.parse("22042B", "7E8037F2278000000\r7E80562042B013F0000\r", "7E8")
        check(pend is ObdResponse.Positive && pend.hex() == "013F", "78 then positive: $pend")
        val vin = Elm327.parse("0902",
            "7E8101449020149424D\r7E82143344B46413630\r7E82231323334353637\r", "7E8")
        check(vin is ObdResponse.Positive && vin.data.size == 18, "multi-frame VIN reassembly: $vin")
        eq(Elm327.parse("010C", "NO DATA\r\r", "7E8"), ObdResponse.NoData, "NO DATA")
        check(Elm327.parse("010C", "?\r\r", "7E8") is ObdResponse.Error, "'?' is an error")
        check(Elm327.parse("010C", "7E904410C1AF8000000\r", "7E8") is ObdResponse.NoData, "other ECU ignored")
        check(Elm327.parse("22042B", "7E80562042C013F0000\r", "7E8") is ObdResponse.Error, "DID echo mismatch")
        check(Elm327.parse("010C", "7E8 04 41 0C 1A F8 00 00 00\r", "7E8") is ObdResponse.Positive, "spaces tolerated")
    }

    fun decoding(lib: SignalSet) {
        eq(Decoder.bits(byteArrayOf(0x1A, 0xF8.toByte()), 0, 16), 0x1AF8L, "bits 16")
        eq(Decoder.bits(byteArrayOf(0x80.toByte()), 0, 1), 1L, "MSB-first bit 0")
        eq(Decoder.bits(byteArrayOf(0x01), 7, 1), 1L, "bit 7 = LSB")
        val (_, rpm) = lib.signal("RPM")!!
        eq((Decoder.decode(rpm, byteArrayOf(0x1A, 0xF8.toByte())) as Decoded.Num).v, 1726.0, "RPM decode")
        val (_, dp) = lib.signal("DPF1_DP")!!
        val neg = Decoder.decode(dp, byteArrayOf(0x07, 0xFF.toByte(), 0x9C.toByte(), 0, 0, 0, 0)) as Decoded.Num
        eq(neg.v, -1.0, "signed DPF dP")
        val (_, typ) = lib.signal("PF_REGEN_TYPE")!!
        eq(Decoder.decode(typ, byteArrayOf(0, 0x02, 0, 0, 0, 0, 0)), Decoded.Text("Active"), "enum map")
        val (_, ect) = lib.signal("ECT")!!
        eq((Decoder.decode(ect, byteArrayOf(130.toByte())) as Decoded.Num).v, 90.0, "ECT offset")
        eq(VehicleProfile.yearFromVin("JMZXXXXXX00123456"), null, "EU VIN has no year code")
        eq(VehicleProfile.yearFromVin("1HGCM82633A004352"), 2003, "US VIN year")
        check(YearFilter(to = 2016, from = 2022).matches(2016) && !YearFilter(to = 2016, from = 2022).matches(2017), "year filter")
        val ov = lib.commands.first { it.key == "7E0:22042B" }
        check(!ov.validated && ov.source == "overlay", "overlay command unvalidated")
        eq(Dtc.parse(byteArrayOf(0x02, 0x04, 0x01, 0x24, 0xA5.toByte())), listOf("P0401", "P24A5"), "DTC parse with count")
        eq(Dtc.parse(byteArrayOf(0x00)), emptyList<String>(), "no DTCs")
        eq(Dtc.parse(byteArrayOf(0xC1.toByte(), 0x23)), listOf("U0123"), "U-code without count")
    }

    fun discovery(lib: SignalSet): VehicleProfile {
        val fake = FakeCx5()
        val elm = Elm327(fake).also { it.init() }
        val prof = Discoverer(elm, lib, DiscoverOptions(yearOverride = 2017,
            sweep = listOf(SweepRange("7E0", 0x0C40, 0x0C44)), delayMs = 0), sleep = {}).run()
        eq(prof.vin, "JMZXXXXXX00123456", "VIN")
        eq(prof.modelYear, 2017, "year override")
        check("0C" in prof.mode01Supported && "42" in prof.mode01Supported, "bitmap parse ${prof.mode01Supported}")
        eq(prof.results["7E0:010C"]?.support, Support.SUPPORTED, "RPM supported")
        eq(prof.results["7E0:017A"]?.support, Support.UNSUPPORTED, "DPF dP not in bitmap")
        eq(prof.results["7E0:22042B"]?.support, Support.SUPPORTED, "soot candidate supported (after 78)")
        eq(prof.results["7E0:220532"]?.support, Support.UNSUPPORTED, "regen distance candidate NRC31")
        eq(prof.sweepHits.map { it.key }, listOf("7E0:220C42"), "sweep hit")
        check(fake.sent.none { it.startsWith("2E") || it.startsWith("31") || it.startsWith("10") || it == "04" },
            "discovery sent nothing but reads")
        val again = VehicleProfile.fromJson(prof.toJson())
        eq(again.results.size, prof.results.size, "profile round-trip")
        eq(again.sweepHits, prof.sweepHits, "sweep hits round-trip")
        println("discovery: ${prof.results.count { it.value.support == Support.SUPPORTED }} supported / ${prof.results.size} probed")
        return prof
    }

    fun logger(lib: SignalSet, prof: VehicleProfile) {
        val fake = FakeCx5()
        val elm = Elm327(fake).also { it.init() }
        val out = mutableListOf<Triple<String, String, Boolean>>()
        var t = 1_000_000L
        val T = TopicScheme.MAZDA_CX5_LEGACY
        val eng = LoggerEngine(elm, lib, prof, { a, b, c -> out += Triple(a, b, c) }, T,
            LoggerConfig(minGapMs = 0, publishMinIntervalMs = 0), now = { t }, sleep = { t += it })
        eng.announce()
        check(out.any { it.first == T.status && it.second == "online" && it.third }, "status online")
        check(out.any { it.first.startsWith("homeassistant/sensor/mazda_cx5_raw_7e0_22042b") }, "raw candidate discovery")
        check(out.none { it.first.contains("mazda_cx5_rpm/") }, "no discovery for legacy RPM")
        check(out.none { it.first.contains("mazda_cx5_trip_active/") }, "legacy: no engine-entity discovery (YAML owns them)")
        check(out.any { it.first == "homeassistant/sensor/mazda_cx5_ect/config" && it.second.contains("\"identifiers\":[\"mazda_cx5\"]") },
            "extra ECT sensor joins existing device")
        fake.rpm = 820.0; fake.speed = 36.0
        repeat(400) { eng.step(); t += 100 }
        check(eng.tripActive, "trip started")
        check(out.any { it.first == T.tripActive && it.second == "on" }, "trip_active on")
        check(out.any { it.first == "mazda/cx5/rpm" && it.second == "820" }, "legacy rpm topic")
        check(out.any { it.first == "mazda/cx5/speed_kmh" && it.second == "36.0" }, "legacy speed topic")
        check(out.any { it.first == T.dtcCodes && it.second == "P0401,P24A5" }, "DTCs published")
        check(out.any { it.first == T.dtcPending && it.second == "none" }, "pending none")
        check(out.any { it.first == "mazda/cx5/raw/7E0_22042B" && it.second.contains("\"hex\":\"013F\"") }, "raw soot candidate")
        check(out.none { it.first == "mazda/cx5/soot_gL" }, "unvalidated soot NOT on legacy topic")
        check(out.any { it.first == "mazda/cx5/oil_dilution" && it.second == "4" }, "oil dilution from OBDb CX5_OIL_DIL_CNT")
        check(out.any { it.first == "mazda/cx5/egt_post_dpf_c" }.not(), "no validated post-DPF EGT source yet")
        fake.rpm = 0.0; fake.speed = 0.0
        repeat(50) { eng.step(); t += 100 }
        check(!eng.tripActive, "trip ended")
        val last = out.lastOrNull { it.first == T.lastTripDist }
        check(last != null && last.second.toDouble() > 0.1 && last.third, "last trip distance retained: $last")
        check(out.any { it.first == T.lastTripRegen && it.second == "no" }, "no regen this trip")
        check(fake.sent.none { !RequestGuard.isAllowed(it) }, "logger sent only allowed commands")
        // Link loss propagates
        fake.alive = false
        var threw = false
        try { repeat(5) { eng.step() } } catch (_: java.io.IOException) { threw = true }
        check(threw, "link loss surfaces as IOException")
    }


    /** Same simulated ECU, but treated as a generic OBDb vehicle with its own topics/device. */
    fun genericLogger(lib: SignalSet, prof: VehicleProfile) {
        val fake = FakeCx5()
        val elm = Elm327(fake).also { it.init() }
        val out = mutableListOf<Triple<String, String, Boolean>>()
        var t = 5_000_000L
        val T = TopicScheme.forVehicle(VehicleRef("Toyota-RAV4", "Toyota", "RAV4"))
        eq(T.base, "car/toyota_rav4", "generic topic base")
        eq(T.deviceId, "carmonitor_toyota_rav4", "generic device id")
        val eng = LoggerEngine(elm, lib, prof, { a, b, c -> out += Triple(a, b, c) }, T,
            LoggerConfig(minGapMs = 0, publishMinIntervalMs = 0, publishCandidates = false), now = { t }, sleep = { t += it })
        eng.announce()
        check(out.any { it.first == "homeassistant/binary_sensor/carmonitor_toyota_rav4_trip_active/config" }, "generic trip entity discovery")
        check(out.any { it.first == "homeassistant/sensor/carmonitor_toyota_rav4_rpm/config" }, "generic RPM gets a sensor")
        check(out.none { it.first.startsWith("mazda/") }, "generic never touches mazda/ topics")
        check(out.none { it.first.contains("_raw_") }, "candidates off -> no raw sensors")
        val dtcCfg = out.first { it.first.endsWith("_dtc_codes/config") }.second
        check(!dtcCfg.contains("availability_topic"), "retained DTC sensor has no availability gate")
        fake.rpm = 900.0; fake.speed = 60.0
        repeat(300) { eng.step(); t += 100 }
        check(out.any { it.first == "car/toyota_rav4/sig/RPM" && it.second == "900" }, "generic RPM topic")
        fake.rpm = 0.0
        repeat(30) { eng.step(); t += 100 }
        val last = out.lastOrNull { it.first == "car/toyota_rav4/trip/last_distance_km" }
        check(last != null && last.second.toDouble() > 0.3, "generic last trip in km: $last")
    }

    fun catalog() {
        eq(ObdbCatalog.parseRepoName("Toyota-RAV4"), VehicleRef("Toyota-RAV4", "Toyota", "RAV4"), "repo split")
        eq(ObdbCatalog.parseRepoName("Mercedes-Benz-C-Class"), VehicleRef("Mercedes-Benz-C-Class", "Mercedes Benz", "C Class"), "multi-word make")
        eq(ObdbCatalog.parseRepoName("Mazda")?.isMakeFallback, true, "make fallback repo")
        eq(ObdbCatalog.parseRepoName("SAEJ1979"), null, "generic repo excluded")
        eq(ObdbCatalog.parseRepoName(".github"), null, "dot repo excluded")
        val page = """[{"name":"Mazda-CX-5"},{"name":"Mazda"},{"name":"Toyota-RAV4"},{"name":"SAEJ1979"},{"name":"Old-Car","archived":true},{"name":"obdb.community"}]"""
        val all = ObdbCatalog.parseReposPage(page)
        eq(all.map { it.repo }, listOf("Mazda-CX-5", "Mazda", "Toyota-RAV4"), "repo page parse")
        eq(ObdbCatalog.search(all, "cx 5").map { it.repo }, listOf("Mazda-CX-5"), "search")
        eq(ObdbCatalog.search(all, "", make = "Mazda").map { it.repo }, listOf("Mazda", "Mazda-CX-5"), "search by make, fallback first")
        eq(ObdbCatalog.makeFallbackFor(all[0], all)?.repo, "Mazda", "make fallback lookup")
        val files = ObdbCatalog.parseDirListing("""[{"name":"default.json"},{"name":"2012-2020.json"},{"name":"2017-2018.json"},{"name":"README.md"}]""")
        eq(ObdbCatalog.chooseFile(files, 2017), "2017-2018.json", "narrowest year range wins")
        eq(ObdbCatalog.chooseFile(files, 2014), "2012-2020.json", "year in wider range")
        eq(ObdbCatalog.chooseFile(files, 2023), "default.json", "fallback to default")
        eq(ObdbCatalog.chooseFile(files, null), "default.json", "no year -> default")
        eq(Wmi.make("JMZXXXXXX00123456"), "Mazda", "WMI Mazda")
        eq(Wmi.make("XXX"), null, "unknown WMI")
        val (tt, tc) = HaDiscovery.connectionTest(TopicScheme.forVehicle(VehicleRef("Toyota-RAV4", "Toyota", "RAV4")))
        eq(tt, "homeassistant/sensor/carmonitor_toyota_rav4_connection_test/config", "test sensor topic")
        check(tc.contains("\"state_topic\":\"car/toyota_rav4/test\"") && !tc.contains("availability"), "test sensor config")
        eq(MiniMqtt.REFUSED[5]?.startsWith("not authorised"), true, "CONNACK rc 5 explained")
    }


    fun haRest() {
        val calls = mutableListOf<Pair<String, String>>()
        var code = 200
        val sink = HaRestSink("https://ha.example.org/", "tok", flushMs = 3_600_000, post = { u, tk, b ->
            check(tk == "tok", "bearer token passed"); calls += u to b; code })
        sink.publish("car/x/sig/RPM", "800", false)
        sink.publish("car/x/sig/RPM", "900", false)          // coalesced: latest wins
        sink.publish("car/x/logger/status", "online", true)
        eq(sink.flush(), 2, "two coalesced messages delivered")
        eq(calls[0].first, "https://ha.example.org/api/services/mqtt/publish", "service URL, trailing slash trimmed")
        check(calls[0].second.contains("\"payload\":\"900\""), "latest payload sent")
        check(calls[1].second.contains("\"retain\":true"), "retain flag forwarded")
        check(sink.connected && sink.lastError == null, "healthy after 200s")
        calls.clear(); code = 401
        sink.publish("car/x/dtc/codes", "P0401", true)
        sink.publish("car/x/sig/VSS", "30", false)
        sink.publish("car/x/trip/last_distance_km", "12.0", true)
        eq(sink.flush(), 0, "401 delivers nothing")
        eq(calls.size, 1, "stops after first 401")
        check(!sink.connected && sink.lastError!!.contains("token"), "401 explained: ${sink.lastError}")
        calls.clear(); code = 200
        eq(sink.flush(), 2, "retained messages retried, live one dropped")
        check(calls.none { it.second.contains("sig/VSS") }, "live value not retried")
        eq(HaRestSink.check("https://ha/", "t") { u, _ -> if (u == "https://ha/api/") 200 else 0 }, null, "API check ok")
        check(HaRestSink.check("https://ha", "t") { _, _ -> 401 }!!.contains("401"), "API check 401")
        check(HaRestSink.check("https://ha", "t") { _, _ -> throw java.io.IOException("no route") }!!.startsWith("Can't reach"), "API check unreachable")
        sink.close()
    }

    fun json() {
        val s = """{"a":[1,2.5,-3e2,"x\"yé"],"b":{"c":null,"d":true}}"""
        val o = MiniJson.obj(s)
        eq(o["a"].asList()[1], 2.5, "json double")
        eq(o["a"].asList()[3], "x\"yé", "json string escapes")
        eq(MiniJson.write(MiniJson.obj(MiniJson.write(o))), MiniJson.write(o), "json round-trip")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        json(); guard(); parsing()
        val lib = library()
        println("library: ${lib.commands.size} commands, ${lib.commands.sumOf { it.signals.size }} signals")
        decoding(lib)
        val prof = discovery(lib)
        logger(lib, prof)
        genericLogger(lib, prof)
        catalog()
        haRest()
        eq(Elm327(FakeCx5()).also { it.init() }.adapterWarnings, emptyList<String>(), "good adapter -> no warnings")
        System.getenv("MQTT_TEST_PORT")?.toIntOrNull()?.let { port ->
            val m = MiniMqtt("127.0.0.1", port, "selftest", "user", "pa55", keepAliveSec = 2,
                will = TopicScheme.MAZDA_CX5_LEGACY.status to "offline")
            m.connect()
            check(m.connected, "mqtt connected")
            m.publish(TopicScheme.MAZDA_CX5_LEGACY.status, "online", retain = true)
            m.publish("mazda/cx5/rpm", "820")
            m.publish("homeassistant/sensor/x/config", "{\"k\":\"" + "v".repeat(300) + "\"}", retain = true)
            Thread.sleep(2500)   // let a PINGREQ go out
            check(m.connected, "still connected after keep-alive")
            m.close()
        }
        println("passed: $passed, failed: ${failures.size}")
        failures.forEach { println("  FAIL $it") }
        if (failures.isNotEmpty()) kotlin.system.exitProcess(1)
    }
}
