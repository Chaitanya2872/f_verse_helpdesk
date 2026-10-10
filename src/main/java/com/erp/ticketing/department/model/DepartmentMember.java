package com.erp.ticketing.department.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "department_members")
@Getter
@Setter
@NoArgsConstructor
public class DepartmentMember {

    @EmbeddedId
    private DepartmentMemberId id = new DepartmentMemberId();

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("departmentId")
    @JoinColumn(name = "department_id")
    private Department department;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("userId")
    @JoinColumn(name = "user_id")
    private com.erp.ticketing.user.model.User user;

    @Column(name = "is_lead", nullable = false)
    private boolean lead = false;

    @Embeddable
    @Getter
    @Setter
    @EqualsAndHashCode
    @NoArgsConstructor
    public static class DepartmentMemberId implements Serializable {

        @Column(name = "department_id")
        private Long departmentId;

        @Column(name = "user_id")
        private Long userId;

        public DepartmentMemberId(Long departmentId, Long userId) {
            this.departmentId = departmentId;
            this.userId = userId;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DepartmentMember other)) return false;
        return Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
