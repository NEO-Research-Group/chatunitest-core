${focal_context}
The existing test suite of the focal method is below. All its tests compile and pass:
```
${suite_tests}
```

Mutation testing with PIT shows that the assertions of the suite are weak: ${score}
<#if mutants?has_content>
The following mutants of the focal method SURVIVED, that is, all the tests still pass when the code is changed in this way:
${mutants}
</#if>
<#if mutants_nc?has_content>
The following lines of the focal method are not executed by any test, so no test can detect a change in them:
${mutants_nc}
</#if>

Write a new test class `${new_class_name}` in the package `${package}` with new test cases that would detect faults in the focal method, so that each change above makes at least one new test fail.
<#if mutants_nc?has_content>
For the lines that are not executed, choose the inputs and the mock configuration that make the focal method execute them, and check their effect.
</#if>
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
