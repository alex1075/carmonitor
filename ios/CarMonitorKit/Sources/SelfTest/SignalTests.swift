import Foundation
import CarMonitorKit

func signalTests() throws {
    let set = try SignalSet.parse(name: "SAEJ1979", json: try repoFile("app/src/main/assets/obdb/SAEJ1979.json"))
    check(set.commands.count > 100, "SAEJ1979 parsed: \(set.commands.count)")
    guard let (rpmCmd, _) = set.signal("RPM"), let (vssCmd, vss) = set.signal("VSS"),
          let (evapCmd, _) = set.signal("EVAP_VP"), let (fsCmd, _) = set.signal("FUELSYS1") else {
        check(false, "signals present"); return
    }
    eq(rpmCmd.request, "010C", "RPM request")
    eq(rpmCmd.key, "7E0:010C", "RPM key")
    eq(rpmCmd.rax, "7E8", "RPM rax")
    eq(Decoder.decodeAll(rpmCmd, Data([0x0B, 0xB8]))["RPM"], .num(750), "RPM 0BB8")
    eq(Decoder.decode(vss, Data([0x32])), .num(50), "VSS 50")
    eq(Decoder.decode(vss, Data([0xFF])), nil, "VSS 255 is nullmax")
    eq(Decoder.decodeAll(evapCmd, Data([0xFF, 0xFC]))["EVAP_VP"], .num(-0.001), "signed EVAP_VP")
    eq(Decoder.decodeAll(fsCmd, Data([0x00, 0x00]))["FUELSYS1"], .text("OFF"), "map lookup")
    eq(Decoder.decode(vss, Data()), nil, "no data, no value")
    eq(Decoder.bits(Data([0b1010_0000]), bix: 0, len: 3), 5, "bits MSB first")
    check(MultiPid.batchable(rpmCmd) && MultiPid.batchable(vssCmd), "0C/0D batchable")
    let split = MultiPid.split(pids: ["0C", "0D"], data: Data([0x0B, 0xB8, 0x0D, 0x32]))
    eq(split["0C"], Data([0x0B, 0xB8]), "split 0C")
    eq(split["0D"], Data([0x32]), "split 0D")
    eq(MultiPid.split(pids: ["0C", "0D"], data: Data([0x0B])).count, 0, "short reply gives nothing")
    let f = YearFilter(from: 2019, to: 2012, years: [2015])
    check(f.matches(2010) && f.matches(2015) && f.matches(2020) && !f.matches(2014), "year filter")
    let over = try SignalSet.parse(name: "o", json: Data(#"{"commands":[{"hdr":"7E0","cmd":{"01":"0C"},"freq":1,"filter":{"from":2030},"dbg":true,"validated":false,"signals":[{"id":"X","fmt":{"len":8}}]}]}"#.utf8))
    let merged = SignalSet.merge(name: "m", [set, over])
    eq(merged.signal("X")?.0.key, "7E0:010C", "overlay replaces the same key")
    check(merged.signal("RPM") == nil, "replaced command's signals gone")
    eq(merged.commands.count, set.commands.count, "merge keeps count")
    check(over.commands[0].debug && !over.commands[0].validated, "dbg and validated flags")
    eq(over.forYear(2017).count, 0, "debug and filtered commands left out")
    eq(set.forYear(nil).count, set.commands.filter { !$0.debug }.count, "no year: all non-debug")
}
