package exchange.benchmark;

import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.profile.LinuxPerfNormProfiler;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.util.concurrent.TimeUnit;

public class BenchmarkRunner {

    private static final String[] JVM_ARGS = {
            "-Xmx4g", "-Xms4g",
            "-XX:+UseZGC", "-XX:+ZGenerational",
            "-XX:+AlwaysPreTouch",
            "-XX:+UnlockDiagnosticVMOptions",
            "-XX:+DebugNonSafepoints",
            "-XX:+PreserveFramePointer",
            "-XX:+UseLargePages",
            "--enable-native-access=ALL-UNNAMED",
            "--add-opens=java.base/sun.misc=ALL-UNNAMED"
    };

    private static final String INCLUDE_FILTER =
            ".*operation_benchmarks.*";

    public static void main(String[] args) throws RunnerException {

        boolean linuxPerf = args.length > 0
                && args[0].equalsIgnoreCase("linux");

        runThroughput(linuxPerf);
        runLatency();
    }

    private static void runThroughput(boolean linuxPerf)
            throws RunnerException {

        System.out.println(
                "PASS 1: THROUGHPUT"
                        + (linuxPerf ? " + GC + HARDWARE COUNTERS" : " + GC")
        );

        OptionsBuilder options = (OptionsBuilder) new OptionsBuilder()
                .include(INCLUDE_FILTER)
                .jvmArgs(JVM_ARGS)
                .mode(Mode.Throughput)
                .timeUnit(TimeUnit.MICROSECONDS)
                .forks(3)
                .warmupIterations(3)
                .warmupTime(TimeValue.seconds(2))
                .measurementIterations(3)
                .measurementTime(TimeValue.seconds(2))
                .addProfiler(GCProfiler.class)
                .result("gen_results/pass1.csv")
                .resultFormat(ResultFormatType.CSV);

        if (linuxPerf) {
            options.addProfiler(LinuxPerfNormProfiler.class);
        }

        new Runner(options.build()).run();
    }

    private static void runLatency() throws RunnerException {

        System.out.println(
                "PASS 2: PURE LATENCY (SampleTime -> percentile distribution)"
        );

        new Runner(new OptionsBuilder()
                .include(INCLUDE_FILTER)
                .jvmArgs(JVM_ARGS)
                .mode(Mode.SampleTime)
                .timeUnit(TimeUnit.MICROSECONDS)
                .forks(3)
                .warmupIterations(3)
                .warmupTime(TimeValue.seconds(2))
                .measurementIterations(3)
                .measurementTime(TimeValue.seconds(3))
                .result("gen_results/pass2.csv")
                .resultFormat(ResultFormatType.CSV)
                .build()
        ).run();
    }
}