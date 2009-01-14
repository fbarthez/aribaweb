/*
    Copyright 2008 Craig Federighi

    Licensed under the Apache License, Version 2.0 (the "License"); you may not use this
    file except in compliance with the License.
    You may obtain a copy of the License at
    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

    $Id: //ariba/platform/ui/metaui/ariba/ui/meta/core/ObjectMeta.java#5 $
*/
package ariba.ui.meta.core;

import ariba.util.fieldvalue.FieldPath;
import ariba.util.fieldvalue.FieldInfo;
import ariba.util.core.ListUtil;
import ariba.util.core.Fmt;
import ariba.util.core.StringUtil;
import ariba.util.core.Assert;
import ariba.ui.aribaweb.util.AWNotificationCenter;
import ariba.ui.aribaweb.util.AWClassLoader;
import ariba.ui.aribaweb.util.AWUtil;
import ariba.ui.aribaweb.util.AWGenericException;
import ariba.ui.aribaweb.core.AWRequestContext;
import ariba.ui.meta.annotations.Action;
import ariba.ui.meta.annotations.Traits;
import ariba.ui.meta.annotations.Properties;
import ariba.ui.meta.annotations.Trait;
import ariba.ui.meta.annotations.Property;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collection;

public class ObjectMeta extends Meta
{
    public final static String KeyClass = "class";
    public final static String KeyField = "field";
    public final static String KeyAction = "action";
    public final static String KeyActionCategory = "actionCategory";
    public final static String KeyObject = "object";
    public final static String KeyValue = "value";
    public final static String KeyType = "type";
    public final static String KeyElementType = "elementType";
    public final static String KeyTrait = "trait";
    public final static String KeyTraits = "traits";
    public static final String KeyTraitGroup = "traitGroup";
    public final static String KeyVisible = "visible";
    public final static String KeyEditable = "editable";
    public final static String KeyValid = "valid";
    public final static String KeyRank = "rank";
    Map<Class, List<AnnotationProcessor>> _annotationProcessors = new HashMap();
    Map<String, String> _traitToGroup;
    int _traitToGroupGeneration = -1;
    private static final FieldPath _FieldPathNullMarker = new FieldPath("null");

    public ObjectMeta()
    {
        registerKeyInitObserver(KeyClass, new IntrospectionMetaProvider());
        registerKeyInitObserver(KeyType, new FieldTypeIntrospectionMetaProvider());

        // These keys define scopes for their properties
        defineKeyAsPropertyScope(KeyField);
        defineKeyAsPropertyScope(KeyAction);
        defineKeyAsPropertyScope(KeyActionCategory);
        defineKeyAsPropertyScope(KeyClass);

        // policies for chaining certain well known properties
        registerPropertyMerger(KeyVisible, new PropertyMerger_And());
        registerPropertyMerger(KeyEditable, new PropertyMerger_And());
        registerPropertyMerger(KeyValid, new PropertyMerger_Valid());

        registerPropertyMerger(KeyClass, Context.PropertyMerger_DeclareList);
        registerPropertyMerger(KeyField, Context.PropertyMerger_DeclareList);
        registerPropertyMerger(KeyTraits, PropertyMerger_Traits);
        registerPropertyMerger(KeyTrait, Context.PropertyMerger_DeclareList);
        registerPropertyMerger(KeyAction, Context.PropertyMerger_DeclareList);
        registerPropertyMerger(KeyActionCategory, Context.PropertyMerger_DeclareList);
        registerPropertyMerger(KeyTraitGroup, Context.PropertyMerger_DeclareList);

        mirrorPropertyToContext(KeyClass, KeyClass);
        mirrorPropertyToContext(KeyType, KeyType);
        mirrorPropertyToContext(KeyElementType, KeyElementType);
        mirrorPropertyToContext(KeyTraits, KeyTrait);
        mirrorPropertyToContext(KeyEditable, KeyEditable);

        registerValueTransformerForKey(KeyObject, Transformer_KeyPresent);

        registerAnnotationListener(Traits.class, new AnnotationProcessor(){
            public void processAnnotation(Annotation annotation, AnnotatedElement prop, List predicateList, Map propertyMap, boolean isAction)
            {
                processTraitsAnnotation((Traits)annotation, prop, propertyMap);
            }
        });

        registerAnnotationListener(Properties.class, new AnnotationProcessor(){
            public void processAnnotation(Annotation annotation, AnnotatedElement prop, List predicateList, Map propertyMap, boolean isAction)
            {
                processPropertiesAnnotation((Properties)annotation, prop, predicateList);
            }
        });

        registerAnnotationListener(Action.class, new AnnotationProcessor(){
            public void processAnnotation(Annotation annotation, AnnotatedElement prop, List predicateList, Map propertyMap, boolean isAction)
            {
                if (isAction) processActionAnnotation((Action)annotation, prop, predicateList);
            }
        });

        Trait.registerAnnotationListeners(this);
        Property.registerAnnotationListeners(this);
    }

    /*
        Provide subclass context with conveniences for getting object field values
     */
    public Context newContext()
    {
        return new ObjectContext(this);
    }
    
    public static class ObjectContext extends Context
    {
        AWRequestContext _requestContext;

        public ObjectContext(ObjectMeta meta)
        {
            super(meta);
        }

        public Object value ()
        {
            Object object = object();
            // Assert.that(object != null, "Call to value() with no current object");
            if (object == null) return null;
            FieldPath fieldPath = fieldPath();
            return (fieldPath != null) ? fieldPath.getFieldValue(object)
                                       : propertyForKey("value");
        }

        public void setValue (Object val)
        {
            Object object = object();
            Assert.that(object != null, "Call to value() with no current object");
            FieldPath fieldPath = fieldPath();
            Assert.that(fieldPath != null, "Can't set derived property (yet)");
            fieldPath.setFieldValue(object, val);
        }

        public FieldPath fieldPath()
        {
            return ((PropertyMap)allProperties()).fieldPath();
        }

        // need to override these -- Extensible gives error if value is queried before being set
        public Object object ()
        {
            return values().get(KeyObject);
        }

        public void setObject (Object object)
        {
            values().put(KeyObject, object);
        }
    }

    // Use a special map subsclass for our Properties
    protected PropertyMap newPropertiesMap ()
    {
        return new PropertyMap();
    }

    protected static class PropertyMap extends Meta.PropertyMap
    {
        FieldPath _fieldPath;

        public FieldPath fieldPath() {
            if (_fieldPath == null) {
                Object value = get(KeyValue);
                String fieldName = (String)get(KeyField);
                _fieldPath = (fieldName != null && value == null)
                        ? new FieldPath(fieldName)
                        : _FieldPathNullMarker;
            }
            return _fieldPath == _FieldPathNullMarker ? null : _fieldPath;
        }
    }

    public static abstract class AnnotationProcessor
    {
        abstract public void processAnnotation(Annotation annotation, AnnotatedElement prop,
                                               List predicateList, Map propertyMap, boolean isAction);
    }

    public void registerAnnotationListener (Class annotationClass, AnnotationProcessor listener)
    {
        List<AnnotationProcessor> listeners = _annotationProcessors.get(annotationClass);
        if (listeners == null) {
            listeners = new ArrayList();
            _annotationProcessors.put(annotationClass, listeners);
        }
        listeners.add(listener);
    }

    void invokeAnnotationListeners (Annotation annotation, AnnotatedElement prop, List predicateList, Map propertyMap, boolean isAction)
    {
        List<AnnotationProcessor> listeners = _annotationProcessors.get(annotation.annotationType());
        if (listeners != null) {
            for (AnnotationProcessor l : listeners) {
                l.processAnnotation(annotation, prop, predicateList, propertyMap, isAction);
            }
        }
    }

    public List<String> fieldNames (Context context)
    {
        return itemNames(context, KeyField);
    }

    public List<String> itemNames (Context context, String key)
    {
        context.push();
        Object contextVal = context.values().get(key);
        if (contextVal == null) contextVal = Meta.KeyAny;
        context.set(key, contextVal);
        context.set(KeyDeclare, key);
        List <String> fieldNames = context.listPropertyForKey(key);
        fieldNames.remove(contextVal);
        context.pop();
        return fieldNames;
    }

    public List<ItemProperties> itemProperties (Context context, String key, boolean filterHidden)
    {
        return itemProperties(context, key, itemNames(context, key), filterHidden);
    }

    public List<ItemProperties> itemProperties (Context context, String key, Collection<String> itemNames,
                                                boolean filterHidden)
    {
        List<ItemProperties> result = new ArrayList();
        for (String itemName : itemNames) {
            context.push();
            context.set(key, itemName);

            // only hidden at this stage if *statically* resolvable to hidden
            Object visible = context.staticallyResolveValue(context.allProperties().get(KeyVisible));
            boolean isHidden = (visible == null) || ((visible instanceof Boolean) && !((Boolean)visible).booleanValue());
            if (!isHidden || !filterHidden) {
                result.add(new ItemProperties(itemName, context.allProperties(), isHidden));
            }
            context.pop();
        }
        return result;
    }

    /*
        "Valid" property support
            valid prop should return false or an error string if error, true or null otherwise
    */
    public static String validationError (Context context)
    {
        Object error = context.propertyForKey(UIMeta.KeyValid);
        if (error == null) return null;
        if (error instanceof Boolean) {
            return ((Boolean)error) ? null : "Invalid entry";
        }
        return error.toString();
    }    

    static class PropertyMerger_Valid implements PropertyMerger, PropertyMergerIsChaining
    {
        public Object merge(Object orig, Object override, boolean isDeclare) {
            // if first is error (error message or false, it wins), otherwise second
            return (override instanceof String
                    || (override instanceof Boolean && !((Boolean)override).booleanValue()))
                ? override
                : orig;
        }

        public String toString()
        {
            return "VALIDATE";
        }
    }
        
    public PropertyMerger PropertyMerger_Traits =  new PropertyMergerDynamic()
    {
        public Object merge(Object orig, Object override, boolean isDeclare) {

            // if we're override a single element with itself, don't go List...
            if (!(orig instanceof List) && !(override instanceof List)
                    && objectEquals(orig, override)) {
                return orig;
            }

            List<Object> origL = toList(orig);
            List<Object> overrideL = toList(override);
            List result = ListUtil.list();
            for (Object trait : origL) {
                if (trait instanceof OverrideValue) trait = ((OverrideValue)trait).value();
                boolean canAdd = true;
                String group = groupForTrait((String)trait);
                if (group != null) {
                    for (Object overrideTrait: overrideL) {
                        if (overrideTrait instanceof OverrideValue) overrideTrait = ((OverrideValue)overrideTrait).value();
                        if (group.equals(groupForTrait((String)overrideTrait))) {
                            canAdd = false;
                            break;
                        }
                    }
                }
                if (canAdd) result.add(trait);
            }
            ListUtil.addElementsIfAbsent(result, overrideL);
            return result;
        }
    };


    String groupForTrait (String trait)
    {
        if (_traitToGroup == null || _traitToGroupGeneration < ruleSetGeneration()) {
            _traitToGroupGeneration = ruleSetGeneration();
            _traitToGroup = new HashMap();
            Context context = newContext();
            for (String group : itemNames(context, KeyTraitGroup)) {
                context.set(KeyTraitGroup, group);
                for (String name : itemNames(context, ObjectMeta.KeyTrait)) {
                    _traitToGroup.put(name, group);
                }
            }
        }
        return _traitToGroup.get(trait);
    }

    public static void addTraits(List traits, Map map)
    {
        List current = (List)map.get(KeyTraits);
        if (current == null) {
            map.put(KeyTraits, new ArrayList(traits));
        } else {
            current.addAll(traits);
        }
    }

    public static void addTrait(String trait, Map map)
    {
        List current = (List)map.get(KeyTraits);
        if (current == null) {
            map.put(KeyTraits, Arrays.asList(trait));
        } else {
            current.add(trait);
        }
    }

    public void processTraitsAnnotation (Traits fs, AnnotatedElement prop, Map propertyMap)
    {
        Object val = fs.value();
        List traits;
        if (val instanceof String) {
            traits = ListUtil.arrayToList(((String)val).split(" "));
        } else {
            traits = ListUtil.arrayToList((String[])val);
        }
        addTraits(traits, propertyMap);
        Log.meta_detail.debug("---- annotation for field %s -- traits: %s", prop, traits);
    }

    public void processPropertiesAnnotation (String propString, AnnotatedElement prop, List predicateList)
    {
        try {
            new Parser(ObjectMeta.this, propString).processRuleBody(predicateList, null);
        } catch (Error e) {
            throw new AWGenericException(Fmt.S("Error parsing @Properties annotation \"%s\" on %s:%s -- %s",
                    propString, prop.getClass().getName(), prop, e));
        } catch (ParseException e) {
            throw new AWGenericException(Fmt.S("Exception parsing @Properties annotation \"%s\" on %s:%s",
                    propString, prop.getClass().getName(), prop), e);
        }
        Log.meta_detail.debug("---- annotation for field %s -- @Properites: %s", prop, propString);
    }

    public void processPropertiesAnnotation (Properties propInfo, AnnotatedElement prop, List predicateList)
    {
        processPropertiesAnnotation(propInfo.value(), prop, predicateList);
    }

    public void processActionAnnotation (Action annotation, AnnotatedElement prop, List predicateList)
    {
        Method method = (Method)prop;
        boolean isStatic = (method.getModifiers() & Modifier.STATIC) != 0;
        String name = method.getName();
        if ((method.getModifiers() & Modifier.PUBLIC) == 0) {
            Log.meta.debug("ERROR: can't declare non-public fields as Actions: (Class):%s -- ignoring...",
                    name);
            return;
        }
        Map properties = new HashMap();

        List predicates = new ArrayList(predicateList);
        predicates.add(predicateList.size()-1, new Rule.Predicate(KeyActionCategory, annotation.category()));
        if (!isStatic) predicates.add(new Rule.Predicate(KeyObject, KeyAny));
        properties.put(KeyActionCategory, annotation.category());

        if (!isStatic) addTraits(Arrays.asList("instance"), properties);

        // Todo:  Category part of predicate!
        addTraits(Arrays.asList(annotation.ResponseType().name()), properties);

        String message = annotation.message();
        if (!StringUtil.nullOrEmptyString(message)) properties.put("message", message);

        String pageName = annotation.pageName();
        if (!StringUtil.nullOrEmptyString(pageName)) properties.put("pageName", pageName);
        addRule(new Rule(predicates, properties, ClassRulePriority));
    }

    static protected ParameterizedType _parameterizedTypeForProperty (Class src, FieldInfo p)
    {
        Type type;
        Method readMethod = p.getGetterMethod();
        if (readMethod != null
                && ((type = readMethod.getGenericReturnType()) != null)
                && (type instanceof ParameterizedType))
            return (ParameterizedType)type;

        try {
            Field field = src.getDeclaredField(p.getName());
            if (field != null
                    && ((type = field.getGenericType()) != null)
                    && (type instanceof ParameterizedType))
                return (ParameterizedType)type;
        } catch (NoSuchFieldException e) {
            // ignore
        }

        Method writeMethod = p.getSetterMethod();
        Type types[];
        if (writeMethod != null
                && ((types = writeMethod.getGenericParameterTypes()) != null)
                && (types.length ==1) && (types[0] instanceof ParameterizedType))
            return (ParameterizedType)types[0];

        return null;
    }

    static protected String _collectionElementType(Class src, FieldInfo p)
    {
        ParameterizedType pType = _parameterizedTypeForProperty(src, p);
        if (pType != null && (pType.getRawType() instanceof Class)
            && Collection.class.isAssignableFrom((Class)pType.getRawType()))
        {
            Type[] ta = pType.getActualTypeArguments();
            if (ta.length == 1 && ta[0] instanceof Class) {
                return ((Class)ta[0]).getName();
            }
        }
        return null;
    }

    protected void registerChoicesForEnum (Class cls)
    {
        Log.meta.debug("Registering enum choices for: %s ", cls);
        Field[] flds = cls.getDeclaredFields();
        for (Field f : flds) {
            if (f.isEnumConstant()) {
                // Log.meta.debug("enum field: %s ", f);
            }
        }
        Object[] enumConstants = cls.getEnumConstants();
        // Log.meta.debug("enum constants: %s " + enumConstants);
    }

    class IntrospectionMetaProvider implements ValueQueriedObserver, AWNotificationCenter.Observer
    {
        Map<String, RuleSet> _ruleSetsByClassName = new HashMap();

        public IntrospectionMetaProvider ()
        {
            // register for AWReload notification
            AWNotificationCenter.addObserver(AWClassLoader.ClassReloadedTopic, this);
        }

        public void onNotification(String topic, Object data)
        {
            List<String> classNames = (List)data;
            for (String className : classNames) {
                Class cls = AWUtil.classForName(className);
                RuleSet ruleSet = _ruleSetsByClassName.get(className);
                if (ruleSet != null && cls != null) {
                    Log.meta.debug("***** reprocessing reloaded class %s", className);
                    ruleSet.disableRules();
                    registerRulesForClass(cls);
                }
            }
        }

        public void notify(Meta meta, String key, Object value)
        {
            Log.meta.debug("IntrospectionMetaProvider notified of first use of class: %s ", value);
            Class cls = AWUtil.classForName((String)value);
            if (cls != null) {
                registerRulesForClass(cls);
            }
        }

        void registerRulesForClass (Class cls)
        {
            String className = cls.getName();
            Class sc = cls.getSuperclass();
            if (sc != null) {
                keyData(KeyClass).setParent(className, sc.getName());
            }

            beginRuleSet(cls.getName().replace(".", "/") + ".java");

            List predicateList = Arrays.asList(new Rule.Predicate(KeyClass, className));
            Map properties = newPropertiesMap();
            processAnnotations(cls, predicateList, properties, false);
            if (!properties.isEmpty()) {
                Rule r = new Rule(predicateList, properties, ClassRulePriority);
                addRule(r);
            }

            _registerActionsForClass(className, cls);
            _registerFieldsForClass(className, cls);

            RuleSet ruleSet = endRuleSet();
            _ruleSetsByClassName.put(className, ruleSet);
        }

        void _registerActionsForClass (String key, Class cls)
        {
            for (Method method : cls.getDeclaredMethods()) {
                Annotation[] annotations = method.getAnnotations();
                if (annotations != null && annotations.length > 0) {
                    String name = method.getName();
                    Map properties = new HashMap();
                    List <Rule.Predicate>predicates = Arrays.asList(new Rule.Predicate(KeyClass, key),
                                                new Rule.Predicate(KeyAction, name));
                    ListUtil.lastElement(predicates)._isDecl = true;
                    processAnnotations(method, predicates, properties, true);
                    if (!properties.isEmpty()) {
                        Rule r = new Rule(predicates, properties, ClassRulePriority);
                        addRule(r);
                    }
                }
            }
        }

        void processAnnotations(AnnotatedElement prop, List predicateList, Map map, boolean isAction)
        {
            Annotation[] annotations = prop.getAnnotations();
            if (annotations != null) {
                for (Annotation annotation : annotations) {
                    invokeAnnotationListeners(annotation,  prop,  predicateList, map, isAction);
                }
            }
        }

        void _registerFieldsForClass (String key, Class cls)
        {
            FieldInfo.Collection infos =  FieldInfo.fieldInfoForClass(cls);
            for (FieldInfo p :  infos.allFieldInfos()) {
                String name = p.getName();
                Class type = p.getType();
                Method getter = p.getGetterMethod();

                if (type == null || "void".equals(type.getName())
                        || (getter != null && (getter.getAnnotation(Action.class) != null)
                                            ||(getter.getDeclaringClass() == Object.class)))
                    continue;

                // add a rule for Class + Field -> type
                Map m = new HashMap();
                m.put(KeyType, type.getName());

                // List with parameterized type?
                String collectionElementType = _collectionElementType(cls, p);
                if (collectionElementType != null) {
                    m.put(KeyElementType, collectionElementType);
                }
                m.put(KeyField, name);
                m.put(KeyVisible, true);

                List<Rule.Predicate> predicateList = Arrays.asList(new Rule.Predicate(KeyClass, key),
                                                new Rule.Predicate(KeyField, name));
                ListUtil.lastElement(predicateList)._isDecl = true;

                if (p.getSetterMethod() != null) {
                    processAnnotations(p.getSetterMethod(), predicateList, m, false);
                }

                if (!p.isWritable()) {
                    m.put(UIMeta.KeyEditable, false);
                    addTraits(Arrays.asList("derived"), m);
                }

                if (getter != null) {
                    processAnnotations(getter, predicateList, m, false);
                }

                if (p.getField() != null) {
                    processAnnotations(p.getField(), predicateList, m, false);
                }

                m.put(KeyRank, (p.getRank() + 1) * 10);

                Rule r = new Rule(predicateList, m, ClassRulePriority);
                addRule(r);
            }
        }

    }

    class FieldTypeIntrospectionMetaProvider implements ValueQueriedObserver
    {
        public void notify(Meta meta, String key, Object value)
        {
            Log.meta.debug("FieldTypeIntrospectionMetaProvider notified of first use of field type: %s ", value);
            Class cls = AWUtil.classForName((String)value);
            if (cls != null) {
                registerRulesForClass(cls);
            }
        }

        void registerRulesForClass (Class cls)
        {
            String className = cls.getName();
            Class sc = cls.getSuperclass();
            if (sc != null) {
                keyData(KeyType).setParent(className, sc.getName());
            }

            if (cls.isEnum()) {
                registerChoicesForEnum(cls);
            }

            // Do something for non-primitives -- add default trait?
            if (!cls.isPrimitive()) {
                Log.meta.debug("Registering non-primitive field type: %s ", cls);
            }
        }

    }
}
