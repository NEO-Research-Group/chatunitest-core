${focal_context}
The existing test suite of the focal method is below. All its tests compile and pass:
```
${suite_tests}
```

Write a new test class `${new_class_name}` in the package `${package}` with new test cases that would detect faults in the focal method, so that a change in its behaviour makes at least one new test fail.
Check return values, state changes and the interactions with the dependencies (`verify`). Do not assert behaviour that the original code does not have.
The new class contains only new tests (do not copy the tests of the suite), compiles on its own, and all its tests pass on the original code.
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
