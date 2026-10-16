package com.querysense.backend.querybuilder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NameWordsTest {

    @Test
    @DisplayName("every naming style splits into the same words")
    void styles() {
        assertThat(NameWords.phrase("student_name")).isEqualTo("student name");
        assertThat(NameWords.phrase("studentName")).isEqualTo("student name");
        assertThat(NameWords.phrase("StudentName")).isEqualTo("student name");
        assertThat(NameWords.phrase("STUDENT_NAME")).isEqualTo("student name");
        assertThat(NameWords.phrase("student-name")).isEqualTo("student name");
        assertThat(NameWords.phrase("CustomerID")).isEqualTo("customer id");
        assertThat(NameWords.phrase("DepartmentID")).isEqualTo("department id");
        assertThat(NameWords.phrase("XMLData")).isEqualTo("xml data");
        assertThat(NameWords.phrase("address2")).isEqualTo("address 2");
        assertThat(NameWords.phrase("items.productId")).isEqualTo("items product id");
        assertThat(NameWords.phrase("cust_nm")).isEqualTo("cust nm");
        assertThat(NameWords.phrase("_id")).isEqualTo("id");
    }

    @Test
    @DisplayName("words come back as a list, and compact joins them")
    void wordsAndCompact() {
        assertThat(NameWords.words("AdmissionYear")).isEqualTo(List.of("admission", "year"));
        assertThat(NameWords.compact("admission year")).isEqualTo("admissionyear");
        assertThat(NameWords.words("")).isEmpty();
        assertThat(NameWords.words(null)).isEmpty();
    }
}
