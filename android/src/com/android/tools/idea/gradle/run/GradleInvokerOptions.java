/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.idea.gradle.run;

import com.android.ddmlib.IDevice;
import com.android.tools.idea.fd.PatchRunningAppAction;
import com.android.tools.idea.gradle.invoker.GradleInvoker;
import com.android.tools.idea.gradle.util.BuildMode;
import com.android.tools.idea.gradle.util.Projects;
import com.android.tools.idea.run.AndroidRunConfigurationBase;
import com.android.tools.idea.run.DeployTarget;
import com.android.tools.idea.run.DeviceTarget;
import com.intellij.execution.configurations.ModuleRunProfile;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.compiler.CompileScope;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class GradleInvokerOptions {
  @NotNull public final List<String> tasks;
  @Nullable public final BuildMode buildMode;
  @NotNull public final List<String> commandLineArguments;

  private GradleInvokerOptions(@NotNull List<String> tasks, @Nullable BuildMode buildMode, @NotNull List<String> commandLineArguments) {
    this.tasks = tasks;
    this.buildMode = buildMode;
    this.commandLineArguments = commandLineArguments;
  }

  public static GradleInvokerOptions create(@NotNull Project project,
                                            @Nullable DataContext context,
                                            @NotNull RunConfiguration configuration,
                                            @NotNull ExecutionEnvironment env,
                                            @Nullable String userGoal) {
    Collection<IDevice> devices = getTargetDevices(env);
    final List<String> cmdLineArgs = getGradleArgumentsToTarget(devices);

    if (!StringUtil.isEmpty(userGoal)) {
      return new GradleInvokerOptions(Collections.singletonList(userGoal), null, cmdLineArgs);
    }

    final Module[] modules = getModules(project, context, configuration);
    if (canUpdateIncrementally(devices, modules)) {
      // TODO: for now we just let performUpdate do the gradle build, so we return an empty list of tasks
      return new GradleInvokerOptions(Collections.<String>emptyList(), null, Collections.<String>emptyList());
    }

    if (MakeBeforeRunTaskProvider.isUnitTestConfiguration(configuration)) {
      // Make sure all "intermediates/classes" directories are up-to-date.
      Module[] affectedModules = getAffectedModules(project, modules);
      BuildMode buildMode = BuildMode.COMPILE_JAVA;
      List<String> tasks = GradleInvoker.findTasksToExecute(affectedModules, buildMode, GradleInvoker.TestCompileType.JAVA_TESTS);
      return new GradleInvokerOptions(tasks, buildMode, cmdLineArgs);
    }

    BuildMode buildMode = BuildMode.ASSEMBLE;
    GradleInvoker.TestCompileType testCompileType = getTestCompileType(configuration);
    List<String> tasks = GradleInvoker.findTasksToExecute(modules, buildMode, testCompileType);
    return new GradleInvokerOptions(tasks, buildMode, cmdLineArgs);
  }

  @NotNull
  private static Module[] getModules(@NotNull Project project, @Nullable DataContext context, @Nullable RunConfiguration configuration) {
    if (configuration instanceof ModuleRunProfile) {
      // ModuleBasedConfiguration includes Android and JUnit run configurations, including "JUnit: Rerun Failed Tests",
      // which is AbstractRerunFailedTestsAction.MyRunProfile.
      return  ((ModuleRunProfile)configuration).getModules();
    }
    else {
      return Projects.getModulesToBuildFromSelection(project, context);
    }
  }

  @NotNull
  private static Module[] getAffectedModules(@NotNull Project project, @NotNull Module[] modules) {
    final CompilerManager compilerManager = CompilerManager.getInstance(project);
    CompileScope scope = compilerManager.createModulesCompileScope(modules, true, true);
    return scope.getAffectedModules();
  }

  @NotNull
  private static GradleInvoker.TestCompileType getTestCompileType(@Nullable RunConfiguration runConfiguration) {
    String id = runConfiguration != null ? runConfiguration.getType().getId() : null;
    return GradleInvoker.getTestCompileType(id);
  }

  @NotNull
  private static List<String> getGradleArgumentsToTarget(Collection<IDevice> devices) {
    // TODO: fix this once we know exactly what properties are required by Gradle
    return Collections.emptyList();
  }

  @NotNull
  private static Collection<IDevice> getTargetDevices(@NotNull ExecutionEnvironment env) {
    DeployTarget deployTarget = env.getCopyableUserData(AndroidRunConfigurationBase.DEPLOY_TARGET_KEY);
    Collection<IDevice> readyDevices = null;
    if (deployTarget instanceof DeviceTarget) {
      readyDevices = ((DeviceTarget)deployTarget).getDevicesIfReady();
    }
    return readyDevices == null ? Collections.<IDevice>emptyList() : readyDevices;
  }

  private static boolean canUpdateIncrementally(@NotNull Collection<IDevice> devices, @NotNull Module[] modules) {
    if (modules.length != 1) {
      return false;
    }

    Module module = modules[0];
    if (!PatchRunningAppAction.isPatchableApp(module)) {
      return false;
    }

    for (IDevice device : devices) {
      if (!PatchRunningAppAction.isAppRunning(device, module)) {
        return false;
      }
    }

    return true;
  }
}