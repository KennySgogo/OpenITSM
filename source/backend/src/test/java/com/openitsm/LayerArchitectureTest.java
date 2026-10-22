package com.openitsm;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;
import static org.assertj.core.api.Assertions.assertThat;

/** Checks the package and dependency boundaries required by the three-layer layout. */
class LayerArchitectureTest {
    private final Path sourceRoot = Path.of("src/main/java/com/openitsm");

    private List<Path> sourceFiles() throws Exception {
        try (var paths = Files.walk(sourceRoot)) {
            return paths.filter(path -> path.toString().endsWith(".java")
                && !path.getFileName().toString().equals("package-info.java")).toList();
        }
    }

    private List<Class<?>> classes() throws Exception {
        var result = new ArrayList<Class<?>>();
        for (Path file : sourceFiles()) {
            String source = Files.readString(file);
            var matcher = Pattern.compile("package\\s+([\\w.]+);").matcher(source);
            if (matcher.find()) {
                String className = file.getFileName().toString().replace(".java", "");
                result.add(Class.forName(matcher.group(1) + "." + className));
            }
        }
        return result;
    }

    private boolean mapperType(Class<?> type) {
        return type.isAnnotationPresent(Mapper.class) || BaseMapper.class.isAssignableFrom(type);
    }

    @Test
    void controllersCallServicesAndDoNotDependOnMappers() throws Exception {
        var violations = new ArrayList<String>();
        for (Class<?> type : classes()) {
            if (!type.isAnnotationPresent(RestController.class)) continue;
            for (var field : type.getDeclaredFields()) {
                if (mapperType(field.getType())) violations.add(type.getName() + " -> " + field.getType().getName());
            }
        }
        assertThat(violations).as("Controllers must not access database mappers").isEmpty();
    }

    @Test
    void eachServiceInterfaceHasOneImplementation() throws Exception {
        var violations = new ArrayList<String>();
        for (Class<?> type : classes()) {
            if (!type.getPackageName().equals("com.openitsm.service") || !type.isInterface()
                || !type.getSimpleName().endsWith("Service")) continue;
            String implementationName = "com.openitsm.service.impl." + type.getSimpleName() + "Impl";
            try {
                Class<?> implementation = Class.forName(implementationName);
                if (!type.isAssignableFrom(implementation) || !implementation.isAnnotationPresent(Service.class)) {
                    violations.add(implementationName + " does not implement and expose " + type.getName());
                }
            } catch (ClassNotFoundException ex) {
                violations.add("Missing " + implementationName);
            }
        }
        assertThat(violations).as("Service contracts have matching service.impl classes").isEmpty();
    }

    @Test
    void packageLayoutContainsOnlyApprovedPackages() throws Exception {
        var allowed = Set.of(
            "com.openitsm", "com.openitsm.controller", "com.openitsm.service", "com.openitsm.service.impl",
            "com.openitsm.mapper", "com.openitsm.entity", "com.openitsm.dto", "com.openitsm.vo",
            "com.openitsm.config", "com.openitsm.common.exception", "com.openitsm.common.response",
            "com.openitsm.common.constants", "com.openitsm.common.utils", "com.openitsm.ai.client");
        var violations = new ArrayList<String>();
        for (Path path : sourceFiles()) {
            String source = Files.readString(path);
            var matcher = Pattern.compile("package\\s+([\\w.]+);").matcher(source);
            if (!matcher.find()) continue;
            String packageName = matcher.group(1);
            if (!allowed.contains(packageName)) violations.add(path + " declares " + packageName);
            if (packageName.equals("com.openitsm") && !path.getFileName().toString().equals("OpenItsmApplication.java")) {
                violations.add(path + " is outside an approved package");
            }
        }
        assertThat(violations).as("Java sources use the prescribed package layout").isEmpty();
    }

    @Test
    void mapperLayerContainsMyBatisOnlyAndOwnsNoTransactions() throws Exception {
        var violations = new ArrayList<String>();
        for (Class<?> type : classes()) {
            if (!type.getPackageName().equals("com.openitsm.mapper")) continue;
            if (!type.isInterface() || !mapperType(type)) violations.add(type.getName() + " is not a MyBatis mapper interface");
            if (type.isAnnotationPresent(Transactional.class)) violations.add(type.getName() + " owns a transaction");
            for (var method : type.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Transactional.class)) violations.add(type.getName() + "." + method.getName() + " owns a transaction");
            }
        }
        for (Path path : sourceFiles()) {
            String source = Files.readString(path);
            if (!source.matches("(?s).*package com\\.openitsm\\.mapper;.*")) continue;
            for (String line : source.lines().filter(value -> value.startsWith("import ")).toList()) {
                if (line.contains("com.openitsm.service") || line.contains("com.openitsm.controller")
                    || line.contains("jakarta.servlet") || line.contains("org.springframework.http")) {
                    violations.add(path + ": " + line);
                }
            }
        }
        assertThat(violations).as("Mapper interfaces contain database access only").isEmpty();
    }

    @Test
    void productionSourcesDoNotUseSpringJdbcOrRepositoryStereotypes() throws Exception {
        var violations = new ArrayList<String>();
        for (Path path : sourceFiles()) {
            String source = Files.readString(path);
            if (source.contains("org.springframework.jdbc") || source.contains("JdbcTemplate")
                || source.contains("JdbcOperations") || source.contains("@Repository")) {
                violations.add(path.toString());
            }
        }
        assertThat(violations).as("Production persistence uses MyBatis mappers").isEmpty();
    }

    @Test
    void commonContainsOnlyApprovedPackagesAndNoBusinessImports() throws Exception {
        var allowed = Set.of("exception", "response", "constants", "utils");
        var violations = new ArrayList<String>();
        for (Path path : sourceFiles()) {
            String source = Files.readString(path);
            if (!source.matches("(?s).*package com\\.openitsm\\.common\\.[\\w.]+;.*")) continue;
            var matcher = Pattern.compile("package\\s+com\\.openitsm\\.common\\.([\\w]+)").matcher(source);
            if (matcher.find() && !allowed.contains(matcher.group(1))) violations.add(path + " is outside common allowlist");
            for (String line : source.lines().filter(value -> value.startsWith("import com.openitsm.")).toList()) {
                if (line.matches(".*import com\\.openitsm\\.(controller|service|mapper|entity|dto|vo)\\..*")) {
                    violations.add(path + ": " + line);
                }
            }
        }
        assertThat(violations).as("common contains reusable, non-business code").isEmpty();
    }

    @Test
    void entitiesAndTransportTypesFollowTheirSuffixes() throws Exception {
        var violations = new ArrayList<String>();
        for (Class<?> type : classes()) {
            String packageName = type.getPackageName();
            if (packageName.equals("com.openitsm.entity") && !type.getSimpleName().endsWith("Entity")) {
                violations.add(type.getName() + " must end in Entity");
            }
            if (packageName.equals("com.openitsm.dto")
                && !(type.getSimpleName().endsWith("Request") || type.getSimpleName().endsWith("Response"))) {
                violations.add(type.getName() + " must end in Request or Response");
            }
            if (packageName.equals("com.openitsm.vo") && !type.getSimpleName().endsWith("VO")) {
                violations.add(type.getName() + " must end in VO");
            }
        }
        assertThat(violations).as("Entity, DTO, and VO files identify their responsibilities").isEmpty();
    }
}
