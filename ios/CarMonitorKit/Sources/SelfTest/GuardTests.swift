import Foundation
import CarMonitorKit

func guardTests() throws {
    let v = try JSONSerialization.jsonObject(with: try repoFile("spec/guard-vectors.json")) as! [String: Any]
    let allowed = v["allowed"] as! [String], refused = v["refused"] as! [String]
    check(refused.count >= 30, "guard vectors loaded")
    for s in allowed { check(RequestGuard.isAllowed(s), "should allow \(s)") }
    for s in refused { check(!RequestGuard.isAllowed(s), "should refuse \(s.debugDescription)") }
    check(RequestGuard.routeAllowed(hdr: "7e0", rax: "7E8"), "11-bit route")
    check(RequestGuard.routeAllowed(hdr: "7DF", rax: nil), "functional header")
    check(!RequestGuard.routeAllowed(hdr: "18DA10F1", rax: nil), "29-bit header refused")
    check(!RequestGuard.routeAllowed(hdr: "7E0", rax: "18DAF110"), "29-bit filter refused")
}
