package org.example;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.runner.IterationType;

import java.util.concurrent.TimeUnit;

// The benchmark illustrates the problem were bimorphic virtual call could not be inlined at tier 4
// if initially a call site was monomorphic and the call was successfully inlined at tier 3, but then
// the call site became bimorphic.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = BimorphicInliningBenchmark.TRIGGER_ON_ITERATION * 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
public class BimorphicInliningBenchmark {
    // Warmup iteration number to trigger a load of a second class in a hierarchy (resulting in CHA dependencies
    // failures and introduction of a second type at the problematic call site).
    public static final int TRIGGER_ON_ITERATION = 5;

    // Makes a problematic call site bimorphic right from the start by loading a second class at the first
    // warmup iteration, instead of TRIGGER_ON_ITERATION-th.
    @Param("false")
    public boolean alwaysBimorphic = false;

    // "Unlucky" class hierarchy:
    // - at tier 0, type profile will be collected
    //   (so, there'll be an entry with invocation counter for each receiver);
    // - at tier 3, c1 will be able to resolve and inline a virtual call,
    //   as a result invocation will be profiled increasing a basic calls counter;
    // - at some point, second class will be loaded, previously compiled versions will be thrown away,
    //   but when the time will come to recompile the method with virtual calls, C2 won't perform
    //   bimorphic inlining: ProfileData's morphism=2 and non-empty basic calls counter prevents it.
    private ClassHierarchyA mainCalleeA = new ClassHierarchyA.SubclassA();
    // "Lucky" class hierarchy:
    // - in this case, methods to inline are package-private, so at tier 3, it will not be inlined,
    //   as a result invocation will not be profiled and there will be no basic calls counter at the
    //   corresponding bci's profile data, only counters associated with receiver types (collected in tier 0).
    // - when the time comes to recompile the method with two receivers, C2 successfully performs bimorphic inlining.
    private ClassHierarchyB mainCalleeB = new ClassHierarchyB.SubclassA();

    private int iter = 0;

    @Setup(Level.Iteration)
    public void makeCallsiteBimorphic(IterationParams iterationParams) {
        iter++;
        if (alwaysBimorphic ||
                (iterationParams.getType() == IterationType.WARMUP && iter == TRIGGER_ON_ITERATION)) {
            ClassHierarchyA nextA = ClassHierarchyA.constructSecondClassInstance();
            mainCalleeA.next = nextA;
            nextA.next = mainCalleeA;
            ClassHierarchyB nextB = ClassHierarchyB.constructSecondClassInstance();
            mainCalleeB.next = nextB;
            nextB.next = mainCalleeB;
        }
    }

    // Extracted from resources/staticallyResolvableTarget.xml:
    //
    // 568 3      org.example.BimorphicInliningBenchmark::staticallyResolvableTarget (19 bytes)(code size: 688)
    //    @ 15 org.example.ClassHierarchyA::callSiteHolder succeed: inline (end time: 0.2520)
    //      @ 1 org.example.ClassHierarchyA$SubclassA::inlinee succeed: inline (end time: 0.2520)
    //
    // 572 4      org.example.BimorphicInliningBenchmark::staticallyResolvableTarget (19 bytes)(code size: 488)
    //    @ 15 org.example.ClassHierarchyA::callSiteHolder succeed: inline (hot) (end time: 0.2520)
    //      @ 1 org.example.ClassHierarchyA$SubclassA::inlinee succeed: inline (hot) (end time: 0.2520)
    //
    // 572 make_not_entrant
    //
    // 616 4      org.example.BimorphicInliningBenchmark::staticallyResolvableTarget (19 bytes)(code size: 488)
    //    @ 15 org.example.ClassHierarchyA::callSiteHolder succeed: inline (hot) (end time: 4.2740)
    //      @ 1 org.example.ClassHierarchyA::inlinee fail: virtual call (end time: 0.0000)
    //        type profile org.example.ClassHierarchyA -> org.example.ClassHierarchyA$SubclassA (19%)
    //
    // -----
    // +PrintMethodData output:
    // org.example.ClassHierarchyA::callSiteHolder()I
    //  interpreter_invocation_count:       43917
    //  invocation_counter:                 43917
    //  backedge_counter:                       0
    //  decompile_count:                        0
    //  mdo size: 360 bytes
    //
    //   0 fast_aload_0
    //   1 invokevirtual 3 <org/example/ClassHierarchyA.inlinee()I>
    //  0    bci: 1    VirtualCallData    count(25760) nonprofiled_count(0) entries(2)
    //                                    'org/example/ClassHierarchyA$SubclassA'(9033 0.21)
    //                                    'org/example/ClassHierarchyA$SubclassB'(8613 0.20)
    //   4 ireturn
    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public int staticallyResolvableTarget() {
        // Cyclic list consisting of only 1 or 2 instances
        mainCalleeA = mainCalleeA.next;
        // Calls ClassHierarchyA::inlinee
        return mainCalleeA.callSiteHolder();
    }
    // Extracted from resources/staticallyUnresolvableTarget.xml:
    //
    // 568 3      org.example.BimorphicInliningBenchmark::staticallyUnresolvableTarget (19 bytes)(code size: 784)
    //    @ 15 org.example.ClassHierarchyB::callSiteHolder succeed: inline (end time: 0.1480)
    //      @ 1 org.example.ClassHierarchyB::inlinee fail: no static binding (end time: 0.0000)
    //
    // 573 4      org.example.BimorphicInliningBenchmark::staticallyUnresolvableTarget (19 bytes)(code size: 488)
    //    @ 15 org.example.ClassHierarchyB::callSiteHolder succeed: inline (hot) (end time: 0.1480)
    //      @ 1 org.example.ClassHierarchyB::inlinee (0 bytes) (end time: 0.0000)
    //        type profile org.example.ClassHierarchyB -> org.example.ClassHierarchyB$SubclassA (100%)
    //      @ 1 org.example.ClassHierarchyB$SubclassA::inlinee succeed: inline (hot) (end time: 0.1480)
    //
    // 573 uncommon trap null speculate_class_check maybe_recompile
    //  @ 15 org.example.BimorphicInliningBenchmark.staticallyUnresolvableTarget()I
    //    @ 1 org.example.ClassHierarchyB.callSiteHolder()I
    //
    // 619 4      org.example.BimorphicInliningBenchmark::staticallyUnresolvableTarget (19 bytes)(code size: 512)
    //    @ 15 org.example.ClassHierarchyB::callSiteHolder succeed: inline (hot) (end time: 4.1750)
    //      @ 1 org.example.ClassHierarchyB::inlinee (0 bytes) (end time: 0.0000)
    //        type profile org.example.ClassHierarchyB -> org.example.ClassHierarchyB$SubclassA (95%)
    //      @ 1 org.example.ClassHierarchyB$SubclassA::inlinee succeed: inline (hot) (end time: 4.1750)
    //      @ 1 org.example.ClassHierarchyB$SubclassB::inlinee succeed: inline (hot) (end time: 4.1750)
    //
    // -----
    // +PrintMethodData output:
    // org.example.ClassHierarchyB::callSiteHolder()I
    //  interpreter_invocation_count:       45147
    //  invocation_counter:                 45147
    //  backedge_counter:                       0
    //  decompile_count:                        0
    //  mdo size: 360 bytes
    //
    //   0 fast_aload_0
    //   1 invokevirtual 3 <org/example/ClassHierarchyB.inlinee()I>
    //  0    bci: 1    VirtualCallData    trap/ org.example.BimorphicInliningBenchmark::staticallyUnresolvableTarget(class_check recompiled) count(0) nonprofiled_count(0) entries(2)
    //                                    'org/example/ClassHierarchyB$SubclassA'(34797 0.78)
    //                                    'org/example/ClassHierarchyB$SubclassB'(9843 0.22)
    //   4 ireturn
    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public int staticallyUnresolvableTarget() {
        // Cyclic list consisting of only 1 or 2 instances
        mainCalleeB = mainCalleeB.next;
        // Calls ClassHierarchyB::inlinee
        return mainCalleeB.callSiteHolder();
    }
}
