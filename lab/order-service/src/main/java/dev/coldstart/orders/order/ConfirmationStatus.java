package dev.coldstart.orders.order;

public enum ConfirmationStatus {

	/** Handed to the async executor. Still QUEUED after a restart means the e-mail was lost. */
	QUEUED,

	/** Accepted by the e-mail provider. */
	SENT,

	/** Rejected by a full executor: a batch job sends it later, from the database. */
	DEFERRED

}
