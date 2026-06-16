package com.leetcode.dashboard.repository;

import com.leetcode.dashboard.model.Submission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface SubmissionRepository extends JpaRepository<Submission, Long> {

    @Query("SELECT s.id FROM Submission s WHERE s.userId = :userId")
    java.util.Set<Long> findSubmissionIdsByUserId(@Param("userId") String userId);

    @Query(value = """
           SELECT submission_date, COUNT(*)
           FROM submission
           WHERE user_id = :userId AND submission_date BETWEEN :start AND :end
           GROUP BY submission_date
           """, nativeQuery = true)
    List<Object[]> getCountByDay(@Param("userId") String userId,
                                  @Param("start") LocalDate start,
                                  @Param("end") LocalDate end);

    @Query(value = """
           SELECT s.submission_date,
                  SUM(CASE WHEN s.submission_date = fs.min_date THEN 1 ELSE 0 END),
                  SUM(CASE WHEN s.submission_date <> fs.min_date THEN 1 ELSE 0 END)
           FROM submission s
           JOIN (SELECT user_id, leetcode_number, MIN(submission_date) AS min_date
                 FROM submission WHERE user_id = :userId
                 GROUP BY user_id, leetcode_number) fs
             ON s.user_id = fs.user_id AND s.leetcode_number = fs.leetcode_number
           WHERE s.user_id = :userId AND s.submission_date BETWEEN :start AND :end
           GROUP BY s.submission_date
           """, nativeQuery = true)
    List<Object[]> getActivityByDay(@Param("userId") String userId,
                                     @Param("start") LocalDate start,
                                     @Param("end") LocalDate end);
}
