import Foundation
import CarMonitorKit

func catalogTests() throws {
    eq(Catalog.parseRepoName("Toyota-RAV4"), VehicleRef(repo: "Toyota-RAV4", make: "Toyota", model: "RAV4"), "repo split")
    eq(Catalog.parseRepoName("Mercedes-Benz-C-Class"), VehicleRef(repo: "Mercedes-Benz-C-Class", make: "Mercedes Benz", model: "C Class"), "multi-word make")
    eq(Catalog.parseRepoName("Mazda")?.isMakeFallback, true, "make fallback repo")
    eq(Catalog.parseRepoName("SAEJ1979"), nil, "generic repo excluded")
    eq(Catalog.parseRepoName(".github"), nil, "dot repo excluded")
    let page = #"[{"name":"Mazda-CX-5"},{"name":"Mazda"},{"name":"Toyota-RAV4"},{"name":"SAEJ1979"},{"name":"Old-Car","archived":true},{"name":"obdb.community"}]"#
    let all = Catalog.parseReposPage(Data(page.utf8))
    eq(all.map { $0.repo }, ["Mazda-CX-5", "Mazda", "Toyota-RAV4"], "repo page parse")
    eq(Catalog.search(all, query: "cx 5").map { $0.repo }, ["Mazda-CX-5"], "search")
    eq(Catalog.search(all, query: "", make: "Mazda").map { $0.repo }, ["Mazda", "Mazda-CX-5"], "search by make, fallback first")
    eq(Catalog.makeFallback(for: all[0], in: all)?.repo, "Mazda", "make fallback lookup")
    let files = Catalog.parseDirListing(Data(#"[{"name":"default.json"},{"name":"2012-2020.json"},{"name":"2017-2018.json"},{"name":"README.md"}]"#.utf8))
    eq(Catalog.chooseFile(files, year: 2017), "2017-2018.json", "narrowest year range wins")
    eq(Catalog.chooseFile(files, year: 2014), "2012-2020.json", "year in wider range")
    eq(Catalog.chooseFile(files, year: 2023), "default.json", "fallback to default")
    eq(Catalog.chooseFile(files, year: nil), "default.json", "no year -> default")
    eq(Catalog.rawURL(repo: "Mazda-CX-5", file: "default.json").absoluteString,
       "https://raw.githubusercontent.com/OBDb/Mazda-CX-5/main/signalsets/v3/default.json", "raw URL")
    eq(Catalog.makeFromVin("JMZXXXXXX00123456"), "Mazda", "WMI Mazda")
    eq(Catalog.makeFromVin("XXX"), nil, "unknown WMI")
    eq(Catalog.yearFromVin("JMZXXXXXX00123456"), nil, "EU VIN has no year code")
    eq(Catalog.yearFromVin("1HGCM82633A004352"), 2003, "US VIN year")
}
