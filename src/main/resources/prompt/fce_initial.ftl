The focal method is `${method_sig}` in the focal class `${class_name}`,
<#if method_invocation_codes_outerclass?has_content>
    Below are examples of existing code in the project where the target method is invoked.
    These snippets can serve as a reference for understanding how the method is used in contexts.
    <#list method_invocation_codes_outerclass as invoke_code>
        ```
        ${invoke_code}
        ```
    </#list>
<#elseif method_invocation_codes_innerclass?has_content>
    Below are examples of existing code in the focal class where the target method is invoked.
    These snippets can serve as a reference for understanding how the method is used in contexts,
    note that you should initialize the focal class object before invoking the method.
    <#list method_invocation_codes_innerclass as invoke_code>
        ```
        ${invoke_code}
        ```
    </#list>
</#if>
Information of the focal method is
```${full_fm}```.

Focal class source code is ```${focal_class_extension}```.

<#list c_deps?keys as key>
    The brief information of dependent class `${key}` is
    ```${c_deps[key]}```.
</#list>
<#list m_deps?keys as key>
    The brief information of dependent class `${key}` is
    ```${m_deps[key]}```.
</#list>
<#list ext_c_deps?keys as key>
    The source code of external dependent class `${key}` is
    ```${ext_c_deps[key]}```.
</#list>
<#list ext_m_deps?keys as key>
    The source code of external dependent class `${key}` is
    ```${ext_m_deps[key]}```.
</#list>

