# Kết quả triển khai và nghiệm thu

Ngày thực hiện: 2026-11-15 (Asia/Saigon). Môi trường: Windows 11, Oracle JDK 17.0.12, Maven 3.9.9.

## Build và fixture tự động

`mvn -o -B verify`: **BUILD SUCCESS**, **26 tests**, 0 failures, 0 errors, 0 skipped.

JAR: [`../target/impact-tool.jar`](../target/impact-tool.jar). Manifest có `Main-Class: com.demo.impact.Main`. Kiểm tra `jdeps --print-module-deps` trả `java.base,jdk.compiler`; JAR chạy không cần dependency Maven/Spring. Dependency Spring 6.2.11 và JUnit chỉ có scope `test`.

Các fixture kiểm tra trực tiếp:

- Đồng tên khác package/class, overload, declaring type của method kế thừa, `this`, `super`, constructor và constructor reference.
- Nested, local, anonymous class; erasure của generic, primitive, array, varargs và type-use annotation.
- Interface với nhiều implementation, lọc theo receiver, implementation kế thừa và loại implementation bị override trong runtime type tương thích.
- `REFERENCE`/`DEFERRED` giữ đúng nhãn cho cả dispatch; static/final/super không thêm dispatch.
- Spring annotation thật: prefix class, mảng path, hằng chuỗi, GET/POST/ANY, handler độ sâu 0, nhiều route.
- Diagnostics khi thiếu dependency, call mơ hồ, placeholder, annotation tùy biến, mapping thừa kế và verb ở cả hai cấp.
- BFS qua caller trực tiếp/gián tiếp, hội tụ, đệ quy, hai mức bằng chứng, giới hạn độ sâu và selector mơ hồ.
- UTF-8/Unicode, TSV tab/CR/LF/backslash, đường dẫn Windows có khoảng trắng, escape Markdown.
- Root trùng/chồng lấn, trùng tên kiểu giữa input, root ngoài repo, classpath tương đối/thứ tự/entry thiếu.
- Đọc bytecode ngoài repo dưới dạng EXTERNAL; không tự đọc `.java` ngoài phạm vi đã chọn.
- Annotation processor có trong classpath được đăng ký qua service provider nhưng **không được chạy**.
- TSV ổn định, giữ snapshot cũ, writer lock, file TSV thiếu/hỏng, schema không hỗ trợ, reference hỏng và ID trùng.
- Ghi snapshot thất bại khi file con trỏ bị khóa trên Windows vẫn giữ nguyên file cũ; lỗi query/output không làm mất nội dung cũ.

Chi tiết JUnit nằm ở `target/surefire-reports/com.demo.impact.ImpactToolTest.txt`.

## Chạy trên source thực tế

Repo nguồn: `C:\Users\ADMIN\iss-365-project-service`.

Phân tích bản sao source trong `target/real-repo` vì phạm vi ghi của phiên làm việc là workspace `impact-tools`. Repo nguồn không được ghi index hoặc sửa mã. Chọn rõ hai source root:

1. `src/main/java`: 300 file.
2. `target/generated-sources`: 19 file Java được build trước đó.

Classpath gồm 118 entry: 117 JAR trong `BOOT-INF/lib` của `target/ommanisoft.service.jar` có sẵn ở repo nguồn, cộng Lombok 1.18.34 trong Maven cache. Giữ thứ tự classpath của artifact. Không chạy build service, ứng dụng hoặc annotation processor. Artifact build có thời gian sửa 2026-05-13; không khẳng định artifact khớp hoàn toàn với source hiện tại.

Hai lệnh `index` và `query` đã chạy qua **Windows CMD** bằng `java -jar`; `query` cũng được chạy từ thư mục `target` với `--repo` và `--out` tuyệt đối.

```cmd
java -jar target\impact-tool.jar index target\real-repo --source-root src/main/java --source-root target/generated-sources --release 17 --classpath-file target\real-repo\classpath.txt
java -jar target\impact-tool.jar query --repo target\real-repo --method "com.oms.service.domain.services.TaskService#validDto(java.lang.Long,com.oms.service.app.dtos.TaskDto,com.oms.service.domain.entities.Task)" --out reports\impact-real.md
```

| Chỉ số | Kết quả |
|---|---:|
| File Java | 319 |
| Method | 2.008 |
| Cạnh | 6.281 |
| Endpoint | 124 |
| Lời gọi chưa phân giải/chưa có caller | 3.147 |
| Diagnostics | 4.535 |
| Trạng thái | **PARTIAL** |
| Release | 17 |

Diagnostics gồm 1.387 lỗi compiler, 2.618 lời gọi trùng phạm vi lỗi compiler, 475 lời gọi chưa gán caller có tên, 54 symbol chưa phân giải và 1 mapping có điều kiện. Nhiều lỗi compiler là method Lombok sinh như `getHeader`, `setHeader`, `setEnterpriseId`; `-proc:none` cố ý không sinh chúng. Không coi kết quả này là toàn bộ đồ thị runtime.

Lần index đo bằng Stopwatch hoàn tất trong khoảng **10,4 giây**. Trước đó phát hiện việc gọi `JavaFileObject.toUri()` lặp theo mọi cặp call/diagnostic gây truy cập filesystem lớn trên Windows; đã cache đường dẫn và nhóm diagnostics theo source. Kiểm thử và các lần index sau xác nhận bản sửa hoạt động.

So sánh hash giữa hai lần index cùng input: `methods.tsv`, `calls.tsv`, `endpoints.tsv`, `unresolved.tsv` đều **giống hệt**. ID snapshot và timestamp khác nhau theo thiết kế.

## Đối chiếu TaskService.validDto

Chữ ký thực tế:

```text
com.oms.service.domain.services.TaskService#validDto(java.lang.Long,com.oms.service.app.dtos.TaskDto,com.oms.service.domain.entities.Task)
```

Kết quả query có hai caller trực tiếp và hai caller gián tiếp là handler:

| Endpoint | Đường gọi đã đối chiếu bằng source |
|---|---|
| POST /tasks | TaskController.create — TaskController.java:40 → TaskService.create — TaskService.java:102 → TaskService.validDto |
| PUT /tasks/{id} | TaskController.update — TaskController.java:99 → TaskService.update — TaskService.java:280 → TaskService.validDto |

Cả hai đường dùng `DECLARED`. Báo cáo đầy đủ kèm chữ ký, vị trí và diagnostics: [impact-real.md](impact-real.md).

Đã tìm thấy **11 lời gọi cùng tên `validDto` trong source hiện tại**, và kiểm tra đích khai báo của cả 11 bằng index:

| File service | Dòng gọi | Đích khai báo | Thuộc method đang query |
|---|---:|---|---|
| MilestoneService.java | 59 | MilestoneService.validDto | Không |
| MilestoneService.java | 103 | MilestoneService.validDto | Không |
| ProjectWorkflowService.java | 56 | ProjectWorkflowService.validDto | Không |
| ProjectWorkflowService.java | 155 | ProjectWorkflowService.validDto | Không |
| ReportWorkPlanService.java | 57 | ReportWorkPlanService.validDto | Không |
| ReportWorkPlanService.java | 155 | ReportWorkPlanService.validDto | Không |
| RequestService.java | 59 | RequestService.validDto | Không |
| TaskService.java | 102 | TaskService.validDto | Có |
| TaskService.java | 280 | TaskService.validDto | Có |
| WorkPlanService.java | 74 | WorkPlanService.validDto | Không |
| WorkPlanService.java | 245 | WorkPlanService.validDto | Không |

Trong tập đối chiếu này: **2 caller đúng, 0 gắn nhầm, 0 bỏ sót** đối với TaskService.validDto. Chín lời gọi cùng tên ở service khác được loại đúng. Chưa có cơ sở xác định số bỏ sót ngoài tập đã kiểm tra vì snapshot là PARTIAL.

## Các bước nghiệm thu chưa xác nhận

- **IntelliJ Find Usages / Call Hierarchy:** đã tìm thấy cửa sổ `iss-365-project-service – TaskService.java`, nhưng công cụ trả `Computer Use was not approved to use IntelliJ IDEA 2025.3.1.1` khi kích hoạt. Không thực hiện được đối chiếu bằng UI; không coi việc đọc source là kết quả của IntelliJ.
- **Baseline CGC:** đặc tả nhắc 11 kết quả đã thấy nhưng chưa có file/export baseline trong dữ liệu được cung cấp. Đã hỏi đường dẫn. Bảng 11 lời gọi ở trên được dựng từ source hiện tại, không khẳng định đó là cùng tập kết quả CGC cũ.
- **Actuator dev:** chưa có URL/phiên mappings dev được cung cấp; chưa đối chiếu `/actuator/mappings` và không chạy service.
- **Filesystem không hỗ trợ atomic move:** mã từ chối rõ ràng nếu `ATOMIC_MOVE` không được hỗ trợ; phiên này kiểm tra lỗi ghi trên filesystem Windows hiện có, chưa có filesystem khác để thực nghiệm nhánh không hỗ trợ atomic move.

Phần triển khai và các kiểm thử tự động đã hoàn tất; các mục trên cần dữ liệu hoặc quyền truy cập tương ứng để chốt nghiệm thu độc lập.
