# Đặc tả CLI phân tích ảnh hưởng thay đổi method trong Java/Spring Boot

## 1. Mục tiêu và phạm vi

Triển khai hai lệnh `index` và `query`. `index` đọc mã nguồn Java và lưu quan hệ gọi method cùng ánh xạ endpoint Spring MVC. `query` nhận chữ ký của một method, duyệt ngược đồ thị và ghi **một file Markdown** liệt kê method và API có khả năng bị ảnh hưởng, kèm đường gọi và vị trí mã nguồn.

Bản đầu chạy local trên Windows CMD bằng **JDK 17 trở lên**, với JDK hỗ trợ phiên bản Java của repo được phân tích. Dùng API compiler công khai của JDK và hệ thống file. Các ví dụ giả định repo `iss-365-project-service`. Tên DTO, route và chữ ký ví dụ phải được thay bằng dữ liệu thật khi nghiệm thu.

Phạm vi MVP gồm Java source, các module được cung cấp rõ ràng, lời gọi Java có thể phân giải tĩnh và Spring MVC dùng annotation. Các luồng qua reflection, proxy động, AOP, event, message broker, scheduler, routing theo cấu hình runtime và qua ranh giới service thuộc phạm vi mở rộng. Báo cáo ghi giới hạn này để người đọc đánh giá độ phủ.

## 2. Hợp đồng CLI

```cmd
java -jar impact-tool.jar index "C:\Users\ADMIN\iss-365-project-service" --release 17 --classpath-file "C:\temp\project-classpath.txt"
java -jar impact-tool.jar query --repo "C:\Users\ADMIN\iss-365-project-service" --method "com.oms.service.domain.services.TaskService#validDto(com.oms.service.dto.TaskDto)" --out impact.md
```

| Tham số | Quy tắc |
|---|---|
| `index <repo>` | Repo đã tồn tại. Mặc định tìm `src/main/java` dưới repo. Nếu có nhiều source root, yêu cầu chỉ rõ phạm vi bằng `--source-root` lặp lại. Từ chối root trùng lặp và báo nếu hai input cung cấp cùng tên kiểu Java. |
| `--source-root <path>` | Đường dẫn tuyệt đối hoặc tương đối với repo. Có thể thêm generated sources đã có sẵn. Chỉ nhận root nằm trong repo. |
| `--release <n>` | Truyền `--release` cho javac. Mặc định 17, in giá trị thực tế trong tóm tắt và báo cáo. Báo lỗi cấu hình nếu JDK đang chạy không hỗ trợ release được yêu cầu. |
| `--classpath-file <path>` | Tùy chọn. File UTF-8 liệt kê mỗi JAR hoặc thư mục class một dòng, tính đường dẫn tương đối từ thư mục chứa file này. Giữ thứ tự, bỏ dòng trống, báo lỗi nếu một entry được khai báo mà không tồn tại. Với Spring Boot thông thường, lấy các entry từ build Maven/Gradle đã có trong môi trường doanh nghiệp. |
| `query --repo <path>` | Đọc snapshot hiện hành trong `<repo>/.impact-index/`. Có thể chạy từ bất kỳ thư mục nào. |
| `--method <signature>` | Cú pháp `package.Class#name(parameter.Type,int,java.lang.String[])`. Nếu chỉ nhập tên hoặc `package.Class#name`, chỉ chấp nhận khi đúng một kết quả. Với nhiều kết quả, in danh sách đầy đủ để người dùng chọn rồi chạy lại. |
| `--max-depth <n>` | Mặc định 10. Chấp nhận số nguyên không âm. Mỗi cạnh gọi tính một bước. Endpoint có handler là chính method đích nằm ở độ sâu 0. |
| `--out <path>` | Mặc định `impact.md` tại thư mục đang chạy. Ghi UTF-8. In đường dẫn file đã ghi trên stdout. |

`--help` và `--version` dùng được ngoài repo. Thông báo lỗi và cảnh báo gửi stderr. Mã thoát `0` là hoàn tất, kể cả báo cáo hợp lệ không tìm thấy endpoint. Mã `2` là sai tham số, không có method hoặc selector mơ hồ. Mã `3` là chỉ mục thiếu, hỏng hoặc schema không hỗ trợ. Mã `1` là lỗi môi trường, compiler ngừng bất thường hoặc lỗi I/O. Chỉ mục một phần vẫn có thể xuất báo cáo với trạng thái `PARTIAL` hiện rõ.

## 3. Lệnh `index`

### 3.1. Thu thập nguồn và phân tích

1. Chuẩn hóa đường dẫn repo và source root. Duyệt file `.java` theo thứ tự ổn định. Mặc định dùng source sản phẩm, không quét `src/test/java`. Không đi theo symlink. Đọc source UTF-8 và lưu đường dẫn tương đối so với repo với dấu `/`.
2. Mở `JavaCompiler` từ `ToolProvider.getSystemJavaCompiler()`, cấu hình `StandardJavaFileManager` và `DiagnosticCollector`. Dùng `-proc:none`, `--release` và classpath được cung cấp. Tool đọc source mà không gọi Maven/Gradle, không chạy ứng dụng và không kích hoạt annotation processor của repo.
3. Chạy `JavacTask.parse()` rồi `analyze()`. Dùng `TreePathScanner` và `Trees` để đọc khai báo, tham chiếu và vị trí. Mỗi method declaration đủ thông tin sinh một record. Lấy declaring type từ `ExecutableElement`, định danh kiểu lồng bằng binary name và dùng `Types.erasure()` khi tạo danh sách kiểu tham số. Chuẩn hóa varargs thành mảng. Với constructor dùng tên `<init>`. Dòng và cột tính từ 1.
4. Duyệt `MethodInvocationTree`, `NewClassTree` và `MemberReferenceTree` trong thân callable. Lấy symbol được chọn bằng `Trees.getElement(...)` và xác thực owner, kiểu tham số cùng diagnostics liên quan trước khi tạo cạnh. Dùng chữ ký của **khai báo được chọn** để nối, kể cả khi receiver ở code là kiểu con. Ghi vị trí của biểu thức gọi hoặc tham chiếu. Với method reference dùng loại `REFERENCE`, vì việc tạo tham chiếu chưa chứng minh method đã được gọi.
5. Nếu thiếu kiểu, lỗi compile trùng phạm vi lời gọi hoặc đích mơ hồ, ghi `unresolved.tsv` với vị trí cùng lý do. Không nối theo tên đơn thuần. Đích ngoài phạm vi source có thể ghi `EXTERNAL` nếu compiler phân giải được chữ ký. Lời gọi nằm ngoài thân method/constructor có tên, chẳng hạn field initializer, cần có diagnostic về độ phủ nếu MVP chưa gán được caller. Với lambda nằm trong method, gắn vào method bao ngoài và ghi loại `DEFERRED`.

Compiler diagnostics cấp lỗi làm snapshot thành `PARTIAL`. Các lời gọi được phân giải độc lập vẫn có thể được lưu. Nếu compiler không khởi tạo được, không có file Java hoặc không tạo được bất kỳ method hợp lệ nào, lệnh thất bại và giữ snapshot trước đó. In số file đọc, method, cạnh, endpoint, lời gọi chưa phân giải và trạng thái snapshot.

### 3.2. Interface, override và Spring MVC

- Với lời gọi qua interface hoặc method có thể override, cạnh `DECLARED` lưu đích tại thời điểm biên dịch. Dùng `Elements.overrides(...)` và thông tin kiểu receiver để tìm implementation tương thích trong source roots. Lưu các đích có thể chạy dưới dạng `POSSIBLE_DISPATCH`, loại bỏ ứng viên không tương thích. Không thêm ứng viên cho lời gọi private, static, final, constructor hoặc lời gọi `super` được ràng buộc trực tiếp. Một call site có thể sinh nhiều loại cạnh.
- Chỉ mục MVP coi quan hệ dispatch là **khả năng**. Việc Spring chọn bean theo `@Qualifier`, `@Primary`, `@Profile` hay cấu hình điều kiện cần dữ liệu riêng. Nếu chưa phân tích các cấu hình đó, báo cáo giữ nhãn `POSSIBLE_DISPATCH` kể cả khi chỉ thấy một implementation trong repo.
- Thu thập handler có annotation Spring bằng tên kiểu đầy đủ đã phân giải. Hỗ trợ `@RestController`, `@Controller`, `@RequestMapping` và các annotation `@GetMapping`, `@PostMapping`, `@PutMapping`, `@PatchMapping`, `@DeleteMapping` chuẩn. Đọc `value` hoặc `path`, kể cả mảng và hằng chuỗi mà compiler phân giải được. Kết hợp prefix class với từng path của method và các HTTP verb hữu hiệu. Nếu mapping không giới hạn verb, dùng `ANY`. Nếu cả hai cấp khai báo verb, kiểm chứng cách kết hợp bằng fixture Spring tương ứng trước khi hỗ trợ và ghi chẩn đoán nếu chưa xác định được.
- Nếu path phụ thuộc placeholder, annotation tùy biến, handler thừa kế chưa được hỗ trợ hoặc router đăng ký động, ghi chẩn đoán độ phủ. Endpoint được lưu là đường dẫn mapping trong code. Context path, reverse proxy và điều kiện runtime có thể làm URL thực tế khác. Danh sách có thể đối chiếu với Actuator `/actuator/mappings` ở dev nếu service đã cung cấp.

### 3.3. Định dạng lưu trữ

Lưu snapshot dưới `<repo>/.impact-index/snapshots/<id>/`. File `<repo>/.impact-index/current` chứa ID snapshot hiện hành. `manifest.properties` dùng cú pháp Java Properties đọc và ghi bằng UTF-8. Các file TSV có header, UTF-8, một record mỗi dòng. Codec escape `\\`, tab, CR và LF bằng `\\\\`, `\\t`, `\\r` và `\\n`. Trường rỗng biểu thị dữ liệu không có. Dùng cùng codec cho cả đọc và ghi.

| File | Header hoặc khóa bắt buộc |
|---|---|
| `manifest.properties` | `schemaVersion=1`, `snapshotId`, `indexedAtUtc`, `status` (`COMPLETE` hoặc `PARTIAL`), `jdkVersion`, `release`, `files`, `methods`, `calls`, `endpoints`, `diagnostics`. `COMPLETE` biểu thị lần phân tích hoàn tất trong phạm vi hỗ trợ, không bảo đảm độ phủ runtime. |
| `methods.tsv` | `method_id`, `owner`, `name`, `parameter_types`, `source_path`, `line`, `column` |
| `calls.tsv` | `caller_id`, `callee_id`, `source_path`, `line`, `column`, `kind`. Giá trị `kind` gồm `DECLARED`, `POSSIBLE_DISPATCH`, `REFERENCE`, `DEFERRED`, `EXTERNAL`. Với tham chiếu hoặc lời gọi trong lambda, giữ loại `REFERENCE` hoặc `DEFERRED` cho mọi cạnh nội bộ ở call site đó. Đích ngoài source vẫn dùng `EXTERNAL`. |
| `endpoints.tsv` (schema 2) | `http_method`, `route`, `params`, `handler_id`, `source_path`, `line`, `column`. `params` lưu danh sách biểu thức Spring bằng các đoạn `độ_dài:biểu_thức` nối tiếp nhau; schema 1 vẫn đọc được và được hiểu là không có `params`. |
| `unresolved.tsv` | `caller_id`, `source_path`, `line`, `column`, `reason`, `detail`. Dùng cho lời gọi, mapping và diagnostics compiler. `caller_id` có thể rỗng. |

Mọi `caller_id` và `handler_id` phải trỏ đến method nội bộ. Cho phép `callee_id` ngoài bảng ở cạnh `EXTERNAL`. Loại trùng theo toàn bộ khóa record, giữ các dòng gọi khác nhau làm bằng chứng. Sắp xếp ổn định theo ID, đường dẫn, dòng, cột và loại liên kết. Kiểm tra trùng ID method, version và tham chiếu trước khi công bố snapshot.

Tạo snapshot trong thư mục riêng. Khóa thao tác ghi theo repo bằng `FileChannel` lock. Sau khi ghi và xác thực đủ file, thay file con trỏ `current` nguyên tử trong cùng thư mục. Nếu filesystem không hỗ trợ thao tác nguyên tử, thất bại rõ ràng và giữ snapshot trước. `query` đọc con trỏ một lần để cả báo cáo dùng cùng snapshot. Giữ các snapshot cũ trong MVP để các tiến trình đọc đang chạy dùng được.

## 4. Lệnh `query`

1. Tải snapshot và kiểm tra schema. Tìm method chính xác hoặc xử lý selector mơ hồ theo hợp đồng CLI. Tạo map `callee_id → danh sách cạnh đi vào` và map `handler_id → endpoints`.
2. Duyệt ngược theo BFS từ method đích. Giữ trạng thái đã thăm theo cặp `(method_id, loại đường đi)` để dừng vòng đệ quy và vẫn tìm được cả đường `DECLARED` lẫn đường qua cạnh khả năng. Một đường có bất cứ cạnh `POSSIBLE_DISPATCH`, `REFERENCE` hoặc `DEFERRED` nào được xếp loại **có thể**. Các đường chỉ qua cạnh `DECLARED` được ghi là **đã phân giải tĩnh**. Bỏ `EXTERNAL` khỏi truy vấn ảnh hưởng trong repo.
3. Giữ một đường ngắn nhất cho mỗi method và loại đường đi, bẻ hòa theo thứ tự cạnh ổn định. Khi chạm handler, ghi endpoint và đường theo chiều dễ đọc `handler → ... → method đích`. Gộp endpoint theo `(verb, route, handler_id)`, ưu tiên trình bày đường đã phân giải tĩnh nếu có. Nếu còn cạnh hợp lệ chưa duyệt do giới hạn độ sâu, ghi cảnh báo. Tách method đích khỏi danh sách caller kể cả khi tồn tại vòng lặp.
4. Ghi Markdown ra file tạm cạnh `--out`, rồi thay file đích sau khi ghi thành công. Nếu tạo báo cáo thất bại, trả lỗi và giữ file cũ. Escape ký tự Markdown trong tên, route và đường dẫn. Định dạng báo cáo phải ổn định để team đọc và so sánh giữa các lần chạy.

### Nội dung `impact.md`

1. Chữ ký method, file và dòng khai báo, ID và ngày lập chỉ mục, trạng thái `COMPLETE` hoặc `PARTIAL`, độ sâu truy vấn.
2. Bảng API gồm HTTP verb, route, handler, mức bằng chứng và chuỗi gọi. Mỗi bước trong chuỗi dẫn đến vị trí lời gọi dạng `source.java:line`. Nếu không thấy API, ghi “Không tìm thấy đường gọi tới endpoint trong snapshot này”.
3. Bảng method gọi trực tiếp và gián tiếp gồm chữ ký, độ sâu, vị trí lời gọi và mức bằng chứng.
4. Diagnostics liên quan đến các method đã duyệt, tổng diagnostics trong snapshot và các giới hạn ảnh hưởng đến kết quả. Khi tên tính năng nghiệp vụ chưa được team cung cấp, báo cáo chỉ hiển thị endpoint và handler.

Ví dụ kết quả mong đợi trên fixture tổng hợp:

| API có thể bị ảnh hưởng | Mức bằng chứng | Đường gọi |
|---|---|---|
| `POST /tasks` | Đã phân giải tĩnh | `TaskController#create(TaskDto)` → `TaskService#create(TaskDto)` → `TaskService#validDto(TaskDto)` |

Đường gọi cho thấy endpoint có thể đi qua method được sửa. Việc hành vi thực sự thay đổi còn phụ thuộc vào đoạn code sửa, dữ liệu đầu vào và nhánh thực thi.

## 5. Trình tự triển khai và nghiệm thu

Đóng gói thành một JAR có `Main-Class`, chạy bằng `java -jar`. Tách phần CLI, indexer, lưu snapshot và truy vấn thành các lớp theo trách nhiệm. Gói chạy dùng API của JDK. Nếu repo phân tích dùng Maven hoặc Gradle, coi quá trình lấy classpath là thao tác chuẩn bị riêng của repo đó.

1. **CLI và codec.** Chốt cú pháp, định danh method và định dạng snapshot. Kiểm tra đọc lại dữ liệu có Unicode, tab, newline và đường dẫn Windows có khoảng trắng.
2. **Index Java.** Chạy fixture với class cùng tên ở hai package, hai method `validDto` khác class, hai overload cùng class, `this`, `super`, constructor, method reference và lambda. So sánh từng cạnh đích với khai báo Java kỳ vọng. Kiểm tra nested class, tham số nguyên thủy, mảng, generic và varargs.
3. **Truy vấn ảnh hưởng.** Kiểm tra caller trực tiếp, caller gián tiếp, đường hội tụ, đệ quy, giới hạn độ sâu, handler ở độ sâu 0 và nhiều route cho một handler. `query` theo đúng chữ ký `TaskService#validDto(...)` phải loại caller chỉ trỏ tới `MilestoneService#validDto(...)` hoặc overload khác.
4. **Spring MVC và dispatch.** Dùng fixture có prefix class, nhiều path, GET/POST, hai implementation của interface và một handler gọi qua interface. Đường đi qua implementation phải mang nhãn khả năng. Kiểm thử phần thiếu dependency và route dùng placeholder, với diagnostics hiện được trong báo cáo.
5. **Chạy thử trên repo thực.** Lấy classpath từ build đang dùng của team. Đối chiếu `TaskService.validDto(...)` với IntelliJ **Find Usages** và **Call Hierarchy**. Đối chiếu endpoint với mapping dev nếu có. Ghi số caller đúng, gắn nhầm và bỏ sót. Dùng 11 kết quả CGC đã thấy làm dữ liệu so sánh, kiểm tra từng đích gọi trước khi coi một dòng là đúng.
6. **Nghiệm thu thao tác file.** Chạy index hai lần với cùng input và so sánh các TSV. Mô phỏng ghi thất bại và xác nhận `current` vẫn trỏ vào snapshot cũ. Kiểm tra `query` khi snapshot bị thiếu file hoặc schema không tương thích. Chạy hai lệnh mẫu bằng JDK trên Windows CMD.

## Tài liệu tham chiếu

- [JavacTask](https://docs.oracle.com/en/java/javase/17/docs/api/jdk.compiler/com/sun/source/util/JavacTask.html), [Trees](https://docs.oracle.com/en/java/javase/17/docs/api/jdk.compiler/com/sun/source/util/Trees.html), [Elements](https://docs.oracle.com/en/java/javase/17/docs/api/java.compiler/javax/lang/model/util/Elements.html) và [quy tắc Java chọn method](https://docs.oracle.com/javase/specs/jls/se17/html/jls-15.html#jls-15.12).
- [Spring MVC request mapping](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-requestmapping.html) và [Spring Boot Actuator mappings](https://docs.spring.io/spring-boot/api/rest/actuator/mappings.html).
- [Cách CodeGraphContext lập chỉ mục](https://codegraphcontext.github.io/concepts/how-it-works/).
