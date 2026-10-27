package resynth;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Tiny zero-dependency test runner for the JUnit-Jupiter-annotated tests in this package.
 * (The local Maven repo has jupiter-api but not the platform launcher, so we call @Test methods directly.)
 */
public final class TestRunner {
    public static void main(String[] args) throws Exception {
        Class<?>[] classes = {EngineTest.class, PngSynthTest.class};
        int passed = 0;
        List<String> failures = new ArrayList<>();

        for (Class<?> c : classes) {
            Object instance = c.getDeclaredConstructor().newInstance();
            for (Method m : c.getDeclaredMethods()) {
                if (m.getAnnotation(org.junit.jupiter.api.Test.class) == null) continue;
                if (!Modifier.isPublic(m.getModifiers())) m.setAccessible(true);
                String name = c.getSimpleName() + "." + m.getName();
                try {
                    m.invoke(instance);
                    passed++;
                    System.out.println("PASS  " + name);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    failures.add(name);
                    System.out.println("FAIL  " + name);
                    Throwable cause = e.getCause();
                    System.out.println("      " + cause);
                    for (StackTraceElement el : cause.getStackTrace()) {
                        if (el.getClassName().startsWith("resynth")) {
                            System.out.println("      at " + el);
                            break;
                        }
                    }
                }
            }
        }
        System.out.println();
        System.out.println(passed + " passed, " + failures.size() + " failed");
        if (!failures.isEmpty()) System.exit(1);
    }
}
