# Project guidance

- Tool names are lowercase, including in prose and documentation: `ja`, `jig`, `jmh`, and `junit`.
- When changing the JMH provider in `src/com.netflix.tools.jmh`, update the `com.netflix.tools.jmh@<version>` pin in `src/com.netflix.tools.ja/META-INF/com.netflix.tools/tools/jmh.properties` to the release containing those changes. The pin selects our provider, not upstream JMH. Projects activate benchmarking through `org.openjdk.jmh.core`; ja resolves our provider separately. Until an upstream runner module can replace this adapter, releasing a newer ja does not automatically select a newer provider. Coordinate the provider release and pin update so ja does not send new arguments to an older provider.
