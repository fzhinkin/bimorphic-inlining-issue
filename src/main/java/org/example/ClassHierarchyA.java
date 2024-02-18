package org.example;

public abstract class ClassHierarchyA {
    public static final class SubclassA extends ClassHierarchyA {
        @Override
        public int inlinee() {
            return 0;
        }
    }

    public static final class SubclassB extends ClassHierarchyA {
        @Override
        public int inlinee() {
            return 1;
        }
    }

    public int callSiteHolder() {
        return inlinee();
    }

    public abstract int inlinee();

    public ClassHierarchyA next = this;

    public static ClassHierarchyA constructSecondClassInstance() {
        return new SubclassB();
    }
}
