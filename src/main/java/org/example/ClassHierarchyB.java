package org.example;

public abstract class ClassHierarchyB {
    public static final class SubclassA extends ClassHierarchyB {
        @Override
        int inlinee() {
            return 0;
        }
    }

    public static final class SubclassB extends ClassHierarchyB {
        @Override
        int inlinee() {
            return 1;
        }
    }

    public int callSiteHolder() {
        return inlinee();
    }

    // Unlike ClassHierarchyA's inlinee, C1 won't be able to inline this one, because
    // ciMethod::find_monomorphic_target bails out on package-private methods:
    // https://github.com/openjdk/jdk/blob/f50df105912858198809b50432ef5a4ab184528d/src/hotspot/share/ci/ciMethod.cpp#L755
    abstract int inlinee();

    public ClassHierarchyB next = this;

    public static ClassHierarchyB constructSecondClassInstance() {
        return new ClassHierarchyB.SubclassB();
    }
}
