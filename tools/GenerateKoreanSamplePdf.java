import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

public class GenerateKoreanSamplePdf {

    public static void main(String[] args) throws IOException {
        Path output = Paths.get("sample-upload-test-ko-cs.pdf");
        Path fontPath = Paths.get("C:\\Windows\\Fonts\\malgun.ttf");

        List<String> lines = List.of(
                "AI 튜터 테스트용 학습 자료",
                "",
                "주제: 컴퓨터공학 기초 개념",
                "운영체제는 하드웨어와 응용 프로그램 사이에서 자원을 관리하는 시스템 소프트웨어이다.",
                "프로세스는 실행 중인 프로그램을 의미하며, 스레드는 프로세스 내부의 실행 단위이다.",
                "CPU 스케줄링은 여러 프로세스에 CPU 사용 시간을 배분하는 방식이며, 대표적으로 FCFS와 Round Robin이 있다.",
                "자료구조 중 스택은 후입선출 구조이고, 큐는 선입선출 구조이다.",
                "트리는 계층적 데이터를 표현하는 비선형 구조이며, 이진 탐색 트리는 탐색 효율을 높이는 데 자주 사용된다.",
                "데이터베이스 인덱스는 검색 속도를 높이지만, 삽입과 수정 시 추가 비용이 발생할 수 있다.",
                "네트워크에서 TCP는 신뢰성 있는 연결 지향 통신을 제공하고, UDP는 속도가 빠른 비연결형 통신을 제공한다.",
                "HTTP는 웹에서 데이터를 주고받기 위한 응용 계층 프로토콜이며, HTTPS는 여기에 TLS 보안을 추가한 형태이다.",
                "정렬 알고리즘의 시간 복잡도는 입력 크기에 따라 수행 시간이 어떻게 증가하는지를 설명한다.",
                "예를 들어, 병합 정렬은 일반적으로 O(n log n), 버블 정렬은 O(n^2)의 시간 복잡도를 가진다.",
                "",
                "핵심 키워드",
                "운영체제, 프로세스, 스레드, 스케줄링, 스택, 큐, 트리, 인덱스, TCP, UDP, HTTP, HTTPS, 시간 복잡도"
        );

        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);

            PDType0Font font = PDType0Font.load(document, fontPath.toFile());

            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.beginText();
                contentStream.setFont(font, 12);
                contentStream.newLineAtOffset(50, 780);

                for (String line : lines) {
                    contentStream.showText(line);
                    contentStream.newLineAtOffset(0, -24);
                }

                contentStream.endText();
            }

            document.save(output.toFile());
        }
    }
}
