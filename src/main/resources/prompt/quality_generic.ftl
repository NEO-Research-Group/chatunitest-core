${focal_context}
<#if suite_names_only == "true">
The existing test suite of the focal method has these tests (names only, by class). All of them compile and pass:
${suite_tests}
<#else>
The existing test suite of the focal method is below. All its tests compile and pass:
```
${suite_tests}
```
</#if>

Write a new test class `${new_class_name}` in the package `${package}` with new test cases that would detect faults in the focal method, so that a change in its behaviour makes at least one new test fail.
Check return values, state changes and the interactions with the dependencies (`verify`). Do not assert behaviour that the original code does not have.
The new class contains only new tests (do not copy the tests of the suite), compiles on its own, and all its tests pass on the original code.
<#if repeated_tests?has_content>
Your previous answer only repeated tests that are already in the suite (${repeated_tests}). Do not return them again: write different tests, with other inputs, mock configurations or assertions, that check something the suite does not check yet.
</#if>
<#if private_bodies?has_content>
The focal method calls private methods of the focal class. Their source code is below; check their effect through the dependencies, the state or the return value, not with stubs or `verify` of the private methods:
```
${private_bodies}
```
</#if>
<#if improve_fce?has_content>
The source code of the whole focal class is:
```
${improve_fce}
```
</#if>
Return the whole new test class in a single ```java block. You can use JUnit 5, Mockito 3 and reflection. No explanation is needed.
