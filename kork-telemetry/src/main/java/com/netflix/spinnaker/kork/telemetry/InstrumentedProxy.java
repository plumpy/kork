/*
 * Copyright 2018 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.spinnaker.kork.telemetry;

import static com.netflix.spinnaker.kork.telemetry.MetricTags.RESULT_KEY;
import static com.netflix.spinnaker.kork.telemetry.MetricTags.ResultValue.FAILURE;
import static com.netflix.spinnaker.kork.telemetry.MetricTags.ResultValue.SUCCESS;
import static java.lang.String.format;

import com.google.common.base.Strings;
import com.netflix.spectator.api.Id;
import com.netflix.spectator.api.Registry;
import com.netflix.spectator.api.histogram.PercentileTimer;
import com.netflix.spinnaker.kork.telemetry.MetricTags.ResultValue;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Adds automatic instrumentation to a target object's method invocations.
 *
 * <p>Two metrics will be recorded for any target: timing and invocations, with an additional tag
 * for "success", having either the value "success" or "failure".
 *
 * <p>Instrumented methods will be generated at proxy creation time, each associated with a metric
 * name following a pattern of "{namespace}.{method}.{metricName}", where "{metricName}" is either
 * "timing" or "invocations". The namespace is provided at creation time, and is typically unique
 * per target. The "method" is automatically generated, using the method name and parameter count of
 * the method.
 *
 * <p>Instrumented methods can be customized slightly via the {@code Instrumented} annotation:
 *
 * <p>- A method can be ignored, causing no metrics to be collected on it. - Provided a custom
 * metric name, in case auto naming produces naming conflicts. - A list of tags added to the
 * metrics.
 */
public class InstrumentedProxy implements InvocationHandler {

  public static <T> T proxy(Registry registry, Object target, String metricNamespace) {
    return proxy(registry, target, metricNamespace, new HashMap<>());
  }

  @SuppressWarnings("unchecked")
  public static <T> T proxy(
      Registry registry, Object target, String metricNamespace, Map<String, String> tags) {
    final Set<Class<?>> interfaces = new LinkedHashSet<>();
    addHierarchy(interfaces, target.getClass());

    final Class[] proxyInterfaces =
        interfaces.stream().filter(Class::isInterface).toArray(Class[]::new);

    return (T)
        Proxy.newProxyInstance(
            target.getClass().getClassLoader(),
            proxyInterfaces,
            new InstrumentedProxy(registry, target, metricNamespace, tags));
  }

  private static final String INVOCATIONS = "invocations";
  private static final String TIMING = "timing";

  private final Registry registry;
  private final Object target;
  private final String metricNamespace;
  private final Map<String, String> tags;

  private final Map<Method, MethodMetrics> instrumentedMethods = new ConcurrentHashMap<>();
  private final List<Method> seenMethods = new ArrayList<>();

  public InstrumentedProxy(Registry registry, Object target, String metricNamespace) {
    this(registry, target, metricNamespace, new HashMap<>());
  }

  public InstrumentedProxy(
      Registry registry, Object target, String metricNamespace, Map<String, String> tags) {
    this.registry = registry;
    this.target = target;
    this.metricNamespace = metricNamespace;
    this.tags = tags;
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    MethodMetrics methodMetrics = getMethodMetrics(method);
    ResultValue resultValue = FAILURE;
    final long start = System.currentTimeMillis();
    try {
      Object result = method.invoke(target, args);
      resultValue = SUCCESS;
      return result;
    } catch (InvocationTargetException e) {
      throw e.getCause();
    } finally {
      if (methodMetrics != null) {
        registry
            .counter(methodMetrics.invocationsId.withTag(RESULT_KEY, resultValue.toString()))
            .increment();
        recordTiming(methodMetrics.timingId.withTag(RESULT_KEY, resultValue.toString()), start);
      }
    }
  }

  private void recordTiming(Id id, long startTimeMs) {
    PercentileTimer.get(registry, id)
        .record(System.currentTimeMillis() - startTimeMs, TimeUnit.MILLISECONDS);
  }

  private Id invocationId(Method method, Map<String, String> tags) {
    return registry.createId(toMetricId(method, INVOCATIONS), tags);
  }

  private Id invocationId(String methodOverride, Map<String, String> tags) {
    return registry.createId(toMetricId(methodOverride, INVOCATIONS), tags);
  }

  private Id timingId(Method method, Map<String, String> tags) {
    return registry.createId(toMetricId(method, TIMING), tags);
  }

  private Id timingId(String methodOverride, Map<String, String> tags) {
    return registry.createId(toMetricId(methodOverride, TIMING), tags);
  }

  private String toMetricId(Method method, String metricName) {
    String methodName =
        (method.getParameterCount() == 0)
            ? method.getName()
            : format("%s%d", method.getName(), method.getParameterCount());
    return toMetricId(methodName, metricName);
  }

  private String toMetricId(String methodName, String metricName) {
    return format("%s.%s.%s", metricNamespace, methodName, metricName);
  }

  private MethodMetrics getMethodMetrics(Method method) {
    if (!instrumentedMethods.containsKey(method) && !seenMethods.contains(method)) {
      seenMethods.add(method);
      boolean processed = false;
      for (Annotation a : method.getDeclaredAnnotations()) {
        if (a instanceof Instrumented) {
          processed = true;

          Instrumented instrumented = (Instrumented) a;
          if (instrumented.ignore()) {
            return null;
          }

          Map<String, String> methodTags = coalesceTags(method, tags, instrumented.tags());
          if (Strings.isNullOrEmpty(instrumented.metricName())) {
            addInstrumentedMethod(
                instrumentedMethods,
                method,
                new MethodMetrics(timingId(method, methodTags), invocationId(method, methodTags)));
          } else {
            addInstrumentedMethod(
                instrumentedMethods,
                method,
                new MethodMetrics(
                    timingId(instrumented.metricName(), methodTags),
                    invocationId(instrumented.metricName(), methodTags)));
          }
        }
      }

      if (!processed && !instrumentedMethods.containsKey(method)) {
        addInstrumentedMethod(
            instrumentedMethods,
            method,
            new MethodMetrics(timingId(method, tags), invocationId(method, tags)));
      }
    }
    return instrumentedMethods.get(method);
  }

  private Map<String, String> coalesceTags(
      Method method, Map<String, String> classTags, String[] methodTags) {
    if (methodTags.length % 2 != 0) {
      throw new UnevenTagSequenceException(target, method.toGenericString());
    }
    Map<String, String> result = new HashMap<>(classTags);
    for (int i = 0; i < methodTags.length; i = i + 2) {
      result.put(methodTags[i], methodTags[i + 1]);
    }
    return result;
  }

  private void addInstrumentedMethod(
      Map<Method, MethodMetrics> existingMethodMetrics,
      Method method,
      MethodMetrics methodMetrics) {
    if (!isMethodAllowed(method)) {
      return;
    }

    existingMethodMetrics.putIfAbsent(method, methodMetrics);
  }

  private static boolean isMethodAllowed(Method method) {
    return
    // Only instrument public methods
    Modifier.isPublic(method.getModifiers())
        ||
        // Ignore any methods from the root Object class
        Arrays.stream(Object.class.getDeclaredMethods())
            .noneMatch(m -> m.getName().equals(method.getName()));
  }

  private static void addHierarchy(Set<Class<?>> classes, Class<?> cl) {
    if (cl == null) {
      return;
    }

    if (classes.add(cl)) {
      for (Class<?> iface : cl.getInterfaces()) {
        addHierarchy(classes, iface);
      }
      Class<?> superclass = cl.getSuperclass();
      if (superclass != null) {
        addHierarchy(classes, superclass);
      }
    }
  }

  private static class MethodMetrics {
    final Id timingId;
    final Id invocationsId;

    MethodMetrics(Id timingId, Id invocationsId) {
      this.timingId = timingId;
      this.invocationsId = invocationsId;
    }
  }

  private static class MetricNameCollisionException extends IllegalStateException {
    public MetricNameCollisionException(
        Object target, String metricName, Method method1, Method method2) {
      super(
          format(
              "Metric name (%s) collision detected between methods '%s' and '%s' in '%s'",
              metricName,
              method1.toGenericString(),
              method2.toGenericString(),
              target.getClass().getSimpleName()));
    }
  }

  private static class UnevenTagSequenceException extends IllegalStateException {
    public UnevenTagSequenceException(Object target, String method) {
      super(
          format(
              "There are an uneven number of values provided for tags on method '%s' in '%s'",
              method, target.getClass().getSimpleName()));
    }
  }
}
