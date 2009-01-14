/*
    Copyright 1996-2008 Ariba, Inc.

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at
        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

    $Id: //ariba/platform/ui/aribaweb/ariba/ui/aribaweb/test/TestLinkHolder.java#7 $
*/

package ariba.ui.aribaweb.test;

import ariba.ui.aribaweb.core.AWComponent;
import ariba.ui.aribaweb.core.AWRequestContext;
import ariba.ui.aribaweb.core.AWResponseGenerating;
import ariba.ui.aribaweb.util.SemanticKeyProvider;
import ariba.util.core.ClassUtil;
import ariba.util.core.MapUtil;
import ariba.util.core.StringUtil;
import ariba.util.test.StagerArgs;
import ariba.util.test.TestPageLink;
import ariba.util.test.TestParam;
import ariba.util.test.TestStager;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

public class TestLinkHolder implements SemanticKeyProvider
{
    private static String _DefaultFirstLevelCategory = "General";
    private static Map _badCategoryNames = MapUtil.map();


    Annotation _annotation;
    Object _annotatedItem;
    String _type;

    String _displayName;
    String _secondaryName;
    
    String _firstLevelCategory;
    String _secondLevelCategory;

    boolean _hidden = false;

    static {
        _badCategoryNames.put("ariba","ariba");
        _badCategoryNames.put("htmlui","htmlui");
        _badCategoryNames.put("test","test");
    }

    public TestLinkHolder ()
    {
        _hidden = true;
    }

    public TestLinkHolder(Annotation annotation, Object annotatedItem)
    {
        init(annotation, annotatedItem, null);
    }

    public TestLinkHolder(Annotation annotation, Object annotatedItem, String type)
    {
        init(annotation, annotatedItem, type);
    }

    private void init (Annotation annotation, Object annotatedItem, String type)
    {
        _annotation = annotation;
        _annotatedItem = annotatedItem;
        _type = type;

        _displayName = computeDisplayName ();
        _secondaryName = computeSecondaryName();
        if (!StringUtil.nullOrEmptyOrBlankString(_type)) {
            _firstLevelCategory = categoryForClassName(_type, _DefaultFirstLevelCategory);
            _secondLevelCategory = _type;
        }
        else {
            if (_annotatedItem.getClass() == Method.class) {
                Method m = (Method) _annotatedItem;
                _firstLevelCategory = categoryForClassName(
                        m.getDeclaringClass().getName(), _DefaultFirstLevelCategory);
                _secondLevelCategory = m.getDeclaringClass().getName();
            }
            else if (_annotatedItem.getClass() == Class.class) {
                _firstLevelCategory = categoryForClassName(
                        ((Class)_annotatedItem).getName(), _DefaultFirstLevelCategory);
                _secondLevelCategory = ((Class)_annotatedItem).getName();
            }
        }
    }

    public String getType ()
    {
        return _type;
    }
    
    public String getKey (Object receiver, AWComponent component)
    {
        return _displayName;
    }
    
    private String computeSecondaryName ()
    {
        String name = null;
        if (StringUtil.nullOrEmptyOrBlankString(_type)) {
            if (_annotatedItem.getClass() == Method.class) {
                Method m = (Method) _annotatedItem;
                    name = m.getDeclaringClass().getName();
            }
        }
        else {
            name = _type;
        }
        String description = getDescription();
        if (!StringUtil.nullOrEmptyOrBlankString(description)) {
            name = name + ": " + description;
        }
        return name;
    }
    private String computeDisplayName ()
    {
        String displayName = null;
        displayName = AnnotationUtil.getAnnotationName(_annotation);

        if (StringUtil.nullOrEmptyOrBlankString(displayName)) {
            if (_annotatedItem.getClass() == Method.class) {
                Method m = (Method) _annotatedItem;
                displayName = m.getName();
            }
            else if (_annotatedItem.getClass() == Class.class) {
                displayName = ClassUtil.stripPackageFromClassName(((Class) _annotatedItem).getName());
            }
        }
        return displayName;
    }

    public String getSecondLevelCategoryName ()
    {
        return _secondLevelCategory;
    }

    public String getFirstLevelCategoryName ()
    {
        return _firstLevelCategory;
    }

    public String getDisplayName ()
    {
        return _displayName;
    }

    public String getSecondaryName ()
    {
        return _secondaryName;
    }

    private static String categoryForClassName (String className, String defaultValue)
    {
        String categoryName = null;
        String[] strings = className.split("\\.");
        for (String name : strings) {
            if (_badCategoryNames.get(name) == null) {
                categoryName = name;
                break;
            }
        }
        if (StringUtil.nullOrEmptyOrBlankString(categoryName)) {
            categoryName = defaultValue;
        }
        return categoryName;
    }

    public boolean isTestLink ()
    {
        return TestPageLink.class.isAssignableFrom(_annotation.annotationType());
    }

    public boolean isTestStager ()
    {
        return TestStager.class.isAssignableFrom(_annotation.annotationType());
    }

    public boolean isTestLinkParam ()
    {
        return TestParam.class.isAssignableFrom(_annotation.annotationType());
    }

    public String getDescription ()
    {
        return AnnotationUtil.getDescription(_annotation);
    }
    public boolean isActive (AWRequestContext requestContext)
    {
        TestContext testContext = TestContext.getTestContext(requestContext);
        boolean active = true;
        if (requiresParam()) {
            if (_annotatedItem.getClass() == Method.class) {
                Method method = (Method) _annotatedItem;
                Class[] methodTypes = method.getParameterTypes();
                if (useAnnotationType(requestContext, method)) {
                    Class typeOnAnnotation = ClassUtil.classForName(_type);
                    if (testContext.get(typeOnAnnotation) == null) {
                        active = false;
                    }
                }
                else {
                    active = canInvokeMethod(method, testContext);
                }
            }
        }
        if (active) {
            active = checkTestLinkParams(testContext);
        }
        return active;    
    }

    public boolean hasDynamicArgument()
    {
        return getStagerDynamicArgumentClass() != null;
    }

    public Class getStagerDynamicArgumentClass()
    {
        Class dynamicClass = null;
        if (_annotatedItem.getClass() == Method.class) {
            Method method = (Method)_annotatedItem;
            Class[] types = method.getParameterTypes();
            for (Class type : types) {
                if (StagerArgs.class.isAssignableFrom(type)) {
                    dynamicClass = type;
                    break;
                }
            }
        }
        return dynamicClass;
    }

    private boolean canInvokeMethod(Method method, TestContext testContext)
    {
        boolean canInvoke = true;
        Class[] methodTypes = method.getParameterTypes();
        for (int i=0; i<methodTypes.length; i++) {
            if (!isInternalParameter(methodTypes[i]) && testContext.get(methodTypes[i]) == null) {
                canInvoke = false;
                break;
            }
        }
        return canInvoke;
    }

    private boolean isInternalParameter (Class parameter)
    {
        if (parameter == AWRequestContext.class ||
                parameter == TestContext.class || 
                StagerArgs.class.isAssignableFrom(parameter)) {
            return true;
        }
        else {
            return false;
        }
    }
    /*
       Check to see if all of the methods that are annotated with TestLinkParam
       can be invoked with the current state of the TestContext.
     */
    private boolean checkTestLinkParams (TestContext testContext)
    {
        boolean canInvoke = true;
        Class testClass;
        if (_annotatedItem.getClass() == Method.class) {
            Method m = (Method) _annotatedItem;
            testClass = m.getDeclaringClass();
        }
        else {
            testClass = _annotatedItem.getClass();
        }
        Map<Class, Object> annotations = TestLinkManager.instance().annotationsForClass(testClass.getName());
        Set keys = annotations.keySet();
        for (Object key : keys) {
            Annotation annotation = (Annotation)key;
            if (TestParam.class.isAssignableFrom(annotation.annotationType())) {
                Object ref = annotations.get(key);
                if (ref.getClass() == Method.class) {
                    if (!canInvokeMethod((Method)ref, testContext)) {
                        canInvoke = false;
                        break;
                    }
                }
            }
        }
        return canInvoke;
    }

    public boolean isHidden ()
    {
        return _hidden;
    }
    public boolean isInteractive ()
    {
        boolean isInteractive = false;
        if (_annotatedItem.getClass() == Method.class) {
            Method m = (Method) _annotatedItem;
            if (AWResponseGenerating.class.isAssignableFrom(m.getReturnType())) {
                isInteractive = true;
            }
        }
        else {
            if (AWComponent.class.isAssignableFrom(_annotatedItem.getClass())) {
                isInteractive = true;
            }
        }
        return isInteractive;
    }

    public boolean requiresParam ()
    {
        boolean requiresParam = false;
        if (_annotatedItem.getClass() == Method.class) {
            Method method = (Method) _annotatedItem;
            Class[] methodTypes = method.getParameterTypes();

            requiresParam = !allInternalParameters(methodTypes);
        }
        return requiresParam;
    }

    private boolean allInternalParameters (Class[] params)
    {
        boolean allInternal = true;
        for (Class c : params) {
            if (!isInternalParameter(c)) {
                allInternal = false;
            }
        }
        return allInternal;
    }

    private boolean useAnnotationType (AWRequestContext requestContext, Method method)
    {
        boolean useAnnotationType = false;
        TestContext testContext = TestContext.getTestContext(requestContext);
        Class[] parameterTypes = method.getParameterTypes();
        if (_type != null && parameterTypes.length == 1) {
            Class typeOnAnnotation = ClassUtil.classForName(_type);
            Class methodType = parameterTypes[0];
            if (methodType.isAssignableFrom(typeOnAnnotation)) {
                if (testContext.get(typeOnAnnotation) != null) {
                    useAnnotationType = true;
                }
            }
        }
        return useAnnotationType;
    }

    public AWResponseGenerating click (AWRequestContext requestContext)
    {
        AWResponseGenerating page = null;
        if (isTestLink()) {
            page = testLinkClick(requestContext);
        }
        else if (isTestStager()) {
            page = testStagerClick(requestContext);
        }
        return page;
    }

    private AWResponseGenerating testStagerClick (AWRequestContext requestContext)
    {
        AWResponseGenerating page = null;

        TestContext testContext = TestContext.getTestContext(requestContext);
        TestSessionSetup testSessionSetup = TestLinkManager.instance().getTestSessionSetup();
        TestStager annotation = (TestStager)_annotation;

        if (_annotatedItem.getClass() == Method.class) {
            Method m = (Method) _annotatedItem;
            Object obj = AnnotationUtil.getObjectToInvoke(requestContext, m);
            Object sessionState = testSessionSetup.getSessionState();
            try {
                Object res = AnnotationUtil.invokeMethod(requestContext, testContext, m, obj);
                if (res != null) {
                    testContext.put(res);
                }
            }
            finally {
                testSessionSetup.restoreSessionStateIfNeeded(sessionState);
            }
//            }
        }
        return page;
    }

    private AWResponseGenerating testLinkClick (AWRequestContext requestContext)
    {
        AWResponseGenerating page = null;

        TestContext testContext = TestContext.getTestContext(requestContext);
        TestSessionSetup testSessionSetup = TestLinkManager.instance().getTestSessionSetup();
        // If this is a test link, then either call pageForName
        // otherwise, call pageForName, invoke the method specified.

        // After that set any @TestLinkParams that are specified on the page.
        if (_annotatedItem.getClass() == Method.class) {
            Method m = (Method) _annotatedItem;
            Object obj = AnnotationUtil.getObjectToInvoke(requestContext, m);
            Object sessionState = testSessionSetup.getSessionState();
            try {
                if (useAnnotationType(requestContext, m)) {
                    Class typeOnAnnotation = ClassUtil.classForName(_type);
                    Object[] args = new Object[1];
                    args[0] = testContext.get(typeOnAnnotation);
                    page = (AWResponseGenerating)AnnotationUtil.invokeMethod(m, obj, args);
                }
                else {
                    page = (AWResponseGenerating)AnnotationUtil.invokeMethod(requestContext, testContext, m, obj);
                }
            }
            finally {
                testSessionSetup.restoreSessionStateIfNeeded(sessionState);
            }
        }
        else {
            String name = ClassUtil.stripPackageFromClassName(((Class) _annotatedItem).getName());
            page = requestContext.pageWithName(name);
            if (page != null) {
                AnnotationUtil.initializePageTestParams(requestContext, page);
            }
        }
        return page;
    }
}
