-- Supply a new @scenario_base and @capacity before sourcing this file.
-- Each invocation creates a fresh Event fixture; it never deletes existing data.
SET @scenario_base = COALESCE(@scenario_base, 9900120000);
SET @capacity = COALESCE(@capacity, 300);

INSERT INTO et_event(id,title,description,venue,status,created_by)
VALUES (@scenario_base+1, CONCAT('Event order load ',@scenario_base),
        'Isolated load fixture','local-only','PUBLISHED',@scenario_base);
INSERT INTO et_event_session(id,event_id,name,starts_at,ends_at,sales_start_at,sales_end_at,status)
VALUES (@scenario_base+2,@scenario_base+1,'Load session',
        CURRENT_TIMESTAMP + INTERVAL 2 HOUR,CURRENT_TIMESTAMP + INTERVAL 4 HOUR,
        CURRENT_TIMESTAMP - INTERVAL 1 MINUTE,CURRENT_TIMESTAMP + INTERVAL 1 HOUR,'ON_SALE');
INSERT INTO et_ticket_tier(id,session_id,name,unit_price,currency,capacity,available,reserved,allocated,
                           purchase_limit_per_user,status)
VALUES
  (@scenario_base+3,@scenario_base+2,'Tier A',8800,'CNY',@capacity,@capacity,0,0,1,'ON_SALE'),
  (@scenario_base+4,@scenario_base+2,'Tier B',8800,'CNY',@capacity,@capacity,0,0,1,'ON_SALE'),
  (@scenario_base+5,@scenario_base+2,'Tier C',8800,'CNY',@capacity,@capacity,0,0,1,'ON_SALE');

SELECT id,capacity,available,reserved,allocated FROM et_ticket_tier
WHERE id BETWEEN @scenario_base+3 AND @scenario_base+5 ORDER BY id;
