# ChatApp

Frame 기반 프로토콜과 단일 Writer 구조를 사용하는 <b>멀티 유저 TCP 채팅 + 파일 전송 애플리케이션</b>입니다.

이 프로젝트는 <b>동기화 문제, 프레임 경계 문제, 파일 스트리밍</b>을 직접 해결하는 것을 목표로 설계되었습니다.


## 1. 주요 기능
### 1.1 채팅 기능
 * 다중 클라이언트 접속 지원
 * 닉네임 등록 및 변경
 * 전체 채팅
 * 귓속말 (Whisper)
 * 사용자 입장/퇴장 알림
 * 송신/수신 메시지 수 관리

### 1.2 파일 전송 기능
 * 클라이언트 간 <b>직접 파일 전송 (서버는 relay 역할)</b>
 * 파일을 Chunk 단위로 분할 전송
 * 파일 전송 중 채팅 동시 처리 기능
 * 대용량 파일 전송 가능 (스트리밍 기반)
 * 파일 확장자 유지
 * 파일명 충돌 방지 (transferId 기반 저장)
<br>

## 2. 실행 환경
 * Java 17+
 * Kotlin
 * Gradle
 * OS: Windows / macOS / Linux (TCP 소켓 기반)
<br>

## 3. 실행 방법
### 3.1 Gradle build (feat. shadowJar)서버 실행
<pre>./gradlew shadowJar</pre>

#### 서버 실행
<pre>java -jar build/libs/Server.jar</pre>
* 기본 포트 : 8080

#### 클라이언트 실행
<pre>java -jar build/libs/Client.jar</pre>

여러 터미널에서 실행하면 다중 클라이언트 테스트 가능
<br><br>

## 4. 사용자 명령어
### 4.1 이름 등록 (최초 실행 시)
<pre>username</pre>

### 4.2 이름 변경
<pre>/n username</pre>

### 4.3 전체 채팅 (이름 등록 이후)
<pre>message</pre>

### 4.4 귓속말
<pre>/w target_username message</pre>

### 4.5 파일 전송
<pre>/f target_username filepath</pre>
* 예시 :
<pre>/f user1 C:\Users\user\Downloads\example.pdf</pre>
<br>

## 5. 파일 전송 동작 방식
### 5.1 전체 흐름
<pre>
  Client A
  └─ FILE_SEND_REQUEST (메타데이터)
      └─ Server (relay)
          └─ Client B

Client A
  └─ FILE_CHUNK (여러 개)
      └─ Server (payload 그대로 relay)
          └─ Client B

Client A
  └─ FILE_SEND_COMPLETE
      └─ Server
          └─ Client B
</pre>

#### 파일 전송 설계
1. 서버는 파일을 <strong>저장하지 않는다.<strong>
2. 파일은 <strong>Chunk 단위로 분할</strong>
3. Payload는 <strong>서버에서 해석하지 않고 그대로 relay</strong>
4. 클라이언트가 파일을 재조립

### 5.2 파일 저장 규칙 (수신 측)
* 저장 경로 : `./downloads/`
* 파일명 형식 : `<원본파일명>__<transferId>.<파일확장자>`
1. 확장자 유지
2. 동일 파일명 충돌 방지
3. transferId로 추적 가능
<br>

## 6. 네트워크 프로토콜 설계
### 6.1 Frame 구조
모든 데이터는 Frame 단위로 전송됩니다.
<pre>
[1 byte ] FrameType
[4 bytes] Payload Length
[n bytes] Payload
</pre>

#### Frame 기반 프로토콜 설계 이유
TCP는 <strong>메시지 경계가 없는 스트림</strong>이기 때문에 다음 문제가 발생한다.
* 채팅 메시지와 파일 데이터가 섞임
* read/write 경계가 깨짐
* 부분 읽기(partial read) 발생
이를 해결하기 위해 모든 데이터는 Frame으로 감싼다.

### 6.2 FrameType
<table>
<thead>
<tr>
<th>FrameType</th>
<th>설명</th>
</tr>
</thead>
<tbody>
<tr>
<td><strong>JSON_PACKET</strong></td>
<td><strong>채팅/제어용 JSON 패킷</strong></td>
</tr>
<tr>
<td><strong>FILE_CHUNK</strong></td>
<td><strong>파일 데이터</strong></td>
</tr>
</tbody>
</table>

### 6.3 JSON Packet 구조
<pre>
[4 bytes] Packet Length
[4 bytes] PacketType
[n bytes] JSON Body
</pre>
<br>

## 7. 핵심 설계 포인트
### 7.1 단일 Writer Thread (ConnectionService)
* JSON_PACKET, FILE_CHUNK 등 모든 Outbound 데이터는 <strong>Queue를 통해 전송</strong>
* 하나의 Writer Thread만 실제 Socket OutputStream에 접근
* 채팅/파일 전송 동시 수행 시에도 <strong>프레임 깨짐 없음</strong>
* backpressure 적용 (Queue 제한)

#### 구조
<pre>
Multiple Producers
 (chat / file)
       │
       ▼
   BlockingQueue
       │
       ▼
 Single Writer Thread
       │
       ▼
 Socket OutputStream
</pre>

#### backpressure의 의미
| Backpressure : 소비자가 처리할 수 있는 속도보다 생산자가 더 빠를 때, 시스템이 스스로 "속도를 줄이라"고 신호를 보내는 메커니즘

LinkedBlockingQueue로 크기를 제한함으로써
* 송신자가 너무 빠를 경우 block/exception 발생
* 메모리 폭주 방지
* 시스템 전체 안정성 확보

### 7.2 서버 역할
* 파일 저장/해석은 <strong>수행하지 않음</strong>
* Client의 payload를 <strong>그대로 전달(realy)</strong>
* 클라이언트 상태 및 경로 설정(routing) 담당

### 7.3 책임 분리
<table>
<thead>
<tr>
<th>계층</th>
<th>책임</th>
</tr>
</thead>
<tbody>
<tr>
<td><strong>JSON_PACKET</strong></td>
<td><strong>사용자 입력, UI 출력</strong></td>
</tr>
<tr>
<td><strong>ClientHandler</strong></td>
<td><strong>서버 측 세션 관리</strong></td>
</tr>
<tr>
<td><strong>ConnectionService</strong></td>
<td><strong>Frame/Socket I/O</strong></td>
</tr>
<tr>
<td><strong>Protocol</strong></td>
<td><strong>JSON Packet encoding/decoding</strong></td>
</tr>
</tbody>
</table>
<br>


## 8. 예외 및 안정성 처리
* 정상 종료와 예외 종료 로그 분리
* DTO 역직렬화 실패 시 즉시 오류 감지
* 잘못된 명령어 입력 방지
* 파일 전송 중 서버/클라이언트 종료 시 안전 종료
<br>

## 9. 현재 제한 사항 및 후속 과제
1. 동시 다중 파일 전송 미지원
2. 파일 전송 재시도(resume) 미지원
3. 전송 중 취소 기능 미구현
4. 암호화(TLS) 미적용
5. 파일 전송 진행률 표시
6. 파일 무결성 체크 (hash)
