@rem Gradle wrapper for AgentCall
@if "%DEBUG"=="" @set DEBUG=1
@setlocal

set DIRNAME=%~dp0
set CLASSPATH=%DIRNAME%gradle\wrapper\gradle-wrapper.jar

@if not exist "%CLASSPATH%" (
    echo Gradle wrapper jar not found
    exit /b 1
)

@rem Default JVM options
set DEFAULT_JVM_OPTS=-Xmx384m -Dfile.encoding=UTF-8

"%JAVA_HOME%/bin/java.exe" %DEFAULT_JVM_OPTS% -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*

@endlocal
