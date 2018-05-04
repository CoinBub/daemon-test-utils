package tech.coinbub.daemon.testutils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Factory;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.hamcrest.StringDescription;

// From https://github.com/sandromancuso/bean-property-matcher/blob/master/src/main/java/org/craftedsw/beanpropertymatcher/matcher/BeanMatcher.java
public class BeanMatcher<T> extends BaseMatcher<T> {

    private final List<BeanPropertyMatcher<?>> propertyMatchers;
    private final Description expectedDescription = new StringDescription();
    private final Description mismatchDescription = new StringDescription();
    private final boolean only;

    @Factory
    public static <T> BeanMatcher<T> has(BeanPropertyMatcher<?>... propertyMatchers) {
        return new BeanMatcher<>(propertyMatchers);
    }

    @Factory
    public static <T> BeanMatcher<T> hasOnly(BeanPropertyMatcher<?>... propertyMatchers) {
        return new BeanMatcher<>(true, propertyMatchers);
    }

    public BeanMatcher(BeanPropertyMatcher<?>... propertyMatchers) {
        this(false, propertyMatchers);
    }

    public BeanMatcher(boolean only, BeanPropertyMatcher<?>... propertyMatchers) {
        this.propertyMatchers = Arrays.asList(propertyMatchers);
        this.only = only;
    }

    @Override
    public boolean matches(Object item) {
        final List<BeanPropertyMatcher<?>> matchers = new ArrayList<>(propertyMatchers);
        if (only) {
            final Set<String> knownFields = propertyMatchers.stream()
                    .map((m) -> (m.getPropertyName()))
                    .collect(Collectors.toSet());
            for (Field field : item.getClass().getDeclaredFields()) {
                if (!knownFields.contains(field.getName()) && !field.isSynthetic()) {
                    matchers.add(BeanPropertyMatcher.property(field.getName(), Matchers.is(Matchers.nullValue())));
                }
            }
        }
        
        boolean matches = true;
        for (BeanPropertyMatcher<?> matcher : matchers) {
            if (!matcher.matches(item)) {
                matches = false;
                appendDescriptions(item, matcher);
            }
        }
        return matches;
    }

    @Override
    public void describeTo(Description description) {
        description.appendText(expectedDescription.toString());
    }

    @Override
    public void describeMismatch(Object item, Description description) {
        description.appendText(mismatchDescription.toString());
    }

    private void appendDescriptions(Object item, Matcher<?> matcher) {
        matcher.describeTo(expectedDescription);
        matcher.describeMismatch(item, mismatchDescription);
        expectedDescription.appendText(" \n");
        mismatchDescription.appendText(" \n");
    }

}
