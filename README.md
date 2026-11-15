# Java/Spring Impact CLI

CLI phân tích tĩnh mã nguồn Java để xác định những method và endpoint Spring MVC có thể bị ảnh hưởng khi một method thay đổi. `index` tạo chỉ mục cho repo. `query` đọc chỉ mục, lần ngược chuỗi gọi và xuất báo cáo Markdown.

JAR của công cụ chạy bằng JDK 17 trở lên và không cần thư viện runtime bên ngoài. JUnit và Spring trong `pom.xml` chỉ dùng cho kiểm thử. Khi phân tích repo Maven, công cụ có thể dùng Maven và các dependency đã lưu trong cache để lấy classpath của repo đó.

## Build và chạy trên Windows

Từ thư mục gốc của repo công cụ, chạy trong CMD:

```cmd
chcp 65001
mvn clean install
java -jar target\impact-tool.jar --help
java -jar target\impact-tool.jar --version
```

JAR đầu ra là `target\impact-tool.jar`. Có thể chạy `mvn -o verify` nếu cache Maven đã có đủ plugin và dependency.

### Lập chỉ mục và truy vấn

Ví dụ dưới đây dùng một repo phân tích nằm cạnh repo công cụ. Thay `..\sample-service` và chữ ký method bằng giá trị của repo bạn muốn phân tích. Dùng `--release` phù hợp với phiên bản Java của repo.

```cmd
java -jar target\impact-tool.jar index "..\sample-service" --release 17
java -jar target\impact-tool.jar query --repo "..\sample-service" --method "com.example.service.SampleService#validate(java.lang.String)" --out "impact.md"
```

Chữ ký có dạng `package.Class#method(kiểu.ThamSố,...)`. Dùng tên nhị phân như `Outer$Inner` cho kiểu lồng, kiểu sau erasure cho generic, kiểu mảng cho varargs và `<init>` cho constructor. Có thể dùng tên method hoặc `package.Class#method` nếu khớp duy nhất. Khi có nhiều kết quả, CLI in các chữ ký phù hợp để chọn lại.

`--repo` trỏ đến đúng thư mục đã chạy `index`. Có thể chạy `query` từ thư mục khác nếu dùng đường dẫn phù hợp đến JAR và repo. `--out` mặc định là `impact.md` trong thư mục hiện hành. Thư mục cha của file đầu ra phải tồn tại. Báo cáo ghi UTF-8 và chỉ thay file cũ sau khi ghi thành công.

## Phạm vi mã nguồn và classpath

Mặc định, CLI tìm `src/main/java`, bỏ qua `src/test/java` và không đi theo symlink. Với repo Maven, CLI tự thêm `target/generated-sources` nếu thư mục đã tồn tại. Khi có nhiều source root, chỉ định từng root bằng `--source-root`. Khi dùng cờ này, CLI lấy đúng danh sách được cung cấp.

```cmd
java -jar target\impact-tool.jar index "..\sample-service" --release 17 ^
  --source-root module-a/src/main/java ^
  --source-root module-b/src/main/java ^
  --source-root module-a/target/generated-sources/annotations ^
  --classpath-file "..\sample-service\classpath.txt"
```

Source root phải nằm trong repo. CLI từ chối các root trùng hoặc chồng lấn và các kiểu Java trùng tên đầy đủ giữa các đầu vào.

File classpath dùng UTF-8, mỗi dòng là một JAR hoặc thư mục chứa file class. Đường dẫn tương đối tính từ thư mục chứa file classpath. CLI giữ nguyên thứ tự và bỏ dòng trống. Nếu danh sách lấy từ Maven có các đường dẫn phân tách bằng `;` trên Windows, tách chúng thành từng dòng trước khi đưa vào `--classpath-file`.

Với repo Maven, khi không truyền `--classpath-file`, CLI chạy Maven offline để lấy compile classpath từ cache. Nếu Maven không lấy được classpath, `index` báo nguyên nhân và giữ snapshot hiện hành. File classpath do người dùng chỉ định luôn được ưu tiên.

Khi compile classpath chứa JAR Lombok, CLI bật riêng processor Lombok để javac nhận diện các member được sinh, đồng thời giữ vị trí lời gọi trong source gốc. Nếu source dùng annotation Lombok nhưng classpath thiếu JAR, CLI ghi `LOMBOK_PROCESSOR_MISSING`. `index` không khởi động ứng dụng hay chạy vòng đời build Maven. Nếu cần generated sources, chuẩn bị chúng bằng quy trình build của repo trước khi lập chỉ mục.

### Chuẩn bị repo thử từ Spring Boot JAR

Nếu đã có Spring Boot JAR, script bên dưới tạo một bản sao mã nguồn và classpath để thử lập chỉ mục. Chạy từ repo công cụ bằng PowerShell 7. Các đường dẫn là ví dụ tương đối.

```powershell
.\scripts\prepare-boot-fixture.ps1 `
  -Repo '..\sample-service' `
  -BootJar '..\sample-service\target\sample-service.jar' `
  -Destination 'target\sample-fixture'

java -jar target/impact-tool.jar index target/sample-fixture --classpath-file target/sample-fixture/classpath.txt
```

Script mặc định sao chép `.java` từ `src/main/java`, giải nén dependency trong `BOOT-INF/lib`, giữ thứ tự `BOOT-INF/classpath.idx` nếu có và ghi nguồn gốc fixture. Script chỉ sao chép mã nguồn đã chọn. Để thêm generated sources, truyền `-SourceRoot @('src/main/java', 'target/generated-sources')` cho script và truyền từng root tương ứng khi chạy `index`. Nếu Spring Boot JAR thiếu Lombok, có thể dùng `-AdditionalClasspath` để đưa vào JAR Lombok tương ứng với phiên bản của repo.

Chọn `-Destination` mới cho mỗi lần chuẩn bị. Khi JAR được build từ phiên bản mã nguồn khác, xem chẩn đoán để phát hiện dependency hoặc kiểu không khớp.

## Đọc báo cáo

| Nhãn cạnh gọi | Ý nghĩa |
| --- | --- |
| `DECLARED` | Khai báo đích do javac phân giải, kể cả method kế thừa từ lớp cha. |
| `POSSIBLE_DISPATCH` | Implementation tương thích có trong mã nguồn. Spring có thể chọn bean khác tùy cấu hình. |
| `REFERENCE` | Tham chiếu method. Việc tạo tham chiếu chưa xác nhận method đã chạy. |
| `DEFERRED` | Lời gọi nằm bên trong lambda. Lambda có thể được thực thi sau đó. |
| `EXTERNAL` | Đích gọi nằm ngoài bảng method nội bộ. Query ngược trong repo bỏ qua cạnh này. |

Query duyệt ngược bằng BFS và giữ một đường ngắn nhất cho mỗi method và mức bằng chứng. Với endpoint có nhiều đường đi, báo cáo ưu tiên đường đã phân giải tĩnh. `--max-depth` mặc định là `10` và chấp nhận `0`. Nếu còn nhánh vượt giới hạn, báo cáo và stderr sẽ cảnh báo. Method đích chỉ xuất hiện với vai trò method được phân tích khi gặp chu trình.

`COMPLETE` cho biết lần phân tích hoàn tất trong phạm vi hỗ trợ. Khi gặp lỗi compiler, CLI ghi trạng thái `PARTIAL`, giữ các cạnh phân giải được và ghi lại chẩn đoán. Báo cáo không tìm thấy endpoint vẫn là một kết quả hợp lệ. Có thể còn đường gọi nằm ngoài độ phủ của chỉ mục.

### Spring MVC và giới hạn phân tích

CLI hỗ trợ annotation Spring MVC chuẩn, hằng chuỗi, mảng path và prefix ở cấp class. Mapping tùy biến, mapping thừa kế và placeholder có thể tạo chẩn đoán. Khi cả cấp class và method đều giới hạn HTTP verb, CLI ghi `MAPPING_BOTH_VERBS` và bỏ qua mapping đó. Các điều kiện `headers`, `consumes` và `produces` được ghi dưới mã `MAPPING_CONDITIONS`.

CLI chưa mô hình hóa reflection, proxy/AOP, event, broker, scheduler, routing runtime và lời gọi qua ranh giới service. Với lời gọi trong field hoặc initializer block mà CLI chưa gán được caller, `UNASSIGNED_CALLER` ghi vị trí và biểu thức gốc để tra cứu.

## Snapshot và mã thoát

Snapshot nằm dưới `.impact-index/snapshots/<id>/` trong repo được phân tích.

| File | Nội dung |
| --- | --- |
| `manifest.properties` | Phiên bản schema, trạng thái và số liệu của snapshot. |
| `methods.tsv` | Chữ ký và vị trí khai báo method. |
| `calls.tsv` | Quan hệ caller–callee và vị trí lời gọi. |
| `endpoints.tsv` | Ánh xạ HTTP method, route và điều kiện tới handler. |
| `unresolved.tsv` | Lỗi, cảnh báo và giới hạn phát hiện khi lập chỉ mục. |

CLI giữ các snapshot cũ và chỉ cập nhật con trỏ `current` sau khi kiểm tra schema, số bản ghi và tham chiếu. Writer khóa theo repo. Reader đọc con trỏ một lần cho mỗi lượt truy vấn. Nếu filesystem không hỗ trợ thao tác thay thế nguyên tử, CLI báo lỗi và giữ con trỏ hoặc file trước đó.

Schema 2 lưu toàn bộ điều kiện Spring `params` trong cột `params` của `endpoints.tsv`. Snapshot schema 1 vẫn đọc được với `params` rỗng. Query gộp endpoint theo HTTP verb, route, handler và toàn bộ danh sách điều kiện. Điều kiện bằng đơn như `v=2.0` hiển thị trong cột Params và trên route, ví dụ `/items/{id}?v=2.0`. Các biểu thức khác được giữ nguyên trong cột Params.

| Mã | Ý nghĩa |
| --- | --- |
| `0` | Hoàn tất, kể cả khi snapshot là `PARTIAL` hoặc báo cáo không có endpoint. |
| `1` | Lỗi môi trường, compiler, I/O hoặc khóa ghi. |
| `2` | Tham số hoặc đầu vào không hợp lệ, hoặc selector không khớp duy nhất. |
| `3` | Snapshot thiếu, hỏng hoặc dùng schema không được hỗ trợ. |

## Cấu trúc và kiểm thử

| Thành phần | Trách nhiệm |
| --- | --- |
| `Main` | Xử lý CLI và điều phối lệnh. |
| `SourceInputs` | Xác định source root và classpath. |
| `JavaIndexer` | Phân tích Java và xác định đích gọi qua dispatch. |
| `SpringMappings` | Trích xuất mapping Spring MVC. |
| `Model`, `Tsv`, `SnapshotStore` | Mô hình dữ liệu, mã hóa TSV và lưu snapshot. |
| `ImpactQuery` | Duyệt chuỗi gọi ngược và xuất Markdown. |

Chạy `mvn verify` để kiểm tra overload, method cùng tên, kiểu lồng, erasure, varargs, constructor, interface và override, lời gọi Spring MVC, vòng đệ quy, giới hạn độ sâu, snapshot một phần, TSV Unicode, khóa ghi và bảo toàn dữ liệu khi ghi lỗi. Báo cáo Surefire nằm ở `target/surefire-reports/`.

Tài liệu đối chiếu gồm [JDK Trees](https://docs.oracle.com/en/java/javase/17/docs/api/jdk.compiler/com/sun/source/util/Trees.html) và [Spring MVC request mapping](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-requestmapping.html).
