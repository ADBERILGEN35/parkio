// Root build script.
//
// The root project is an aggregator only — it contains no production code.
// Build conventions live in the `parkio.spring-service` convention plugin
// (see buildSrc), which each service applies individually.

tasks.register("printServices") {
    group = "help"
    description = "Lists the Parkio service modules."
    doLast {
        subprojects.filter { it.parent?.name == "services" }
            .forEach { println(it.path) }
    }
}
