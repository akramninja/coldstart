package dev.coldstart.orders.order;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

interface OrderRepository extends JpaRepository<CustomerOrder, Long> {

	/**
	 * The order with everything the response needs, in one query: nothing is left to load lazily
	 * once the transaction has ended.
	 */
	@EntityGraph(attributePaths = { "customer", "lines", "lines.product" })
	Optional<CustomerOrder> findWithDetailsById(Long id);

	@Transactional
	@Modifying
	@Query("update CustomerOrder o set o.confirmationStatus = :status where o.id = :id")
	int updateConfirmationStatus(long id, ConfirmationStatus status);

}
