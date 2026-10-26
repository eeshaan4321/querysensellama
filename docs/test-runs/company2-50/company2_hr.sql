CREATE TABLE employee
( emp_id          VARCHAR(8)      NOT NULL,
  first_name      VARCHAR(20)     NOT NULL,
  middle_initial  CHAR(1),
  last_name       VARCHAR(25)     NOT NULL,
  birth_date      DATE,
  address         VARCHAR(40),
  gender          CHAR(1),
  salary          DECIMAL(7),
  manager_id      VARCHAR(8),
  dept_no         INT             NOT NULL,
PRIMARY KEY (emp_id));

CREATE TABLE department
( dept_name        VARCHAR(25)    NOT NULL,
  dept_no          INT            NOT NULL,
  head_emp_id      VARCHAR(8)     NOT NULL,
  head_start_date  DATE,
PRIMARY KEY (dept_no),
UNIQUE      (dept_name),
FOREIGN KEY (head_emp_id) REFERENCES employee(emp_id) );

CREATE TABLE dept_location
( dept_no          INT            NOT NULL,
  location         VARCHAR(20)    NOT NULL,
PRIMARY KEY (dept_no, location),
FOREIGN KEY (dept_no) REFERENCES department(dept_no) );

CREATE TABLE project
( project_name     VARCHAR(25)    NOT NULL,
  project_no       INT            NOT NULL,
  site             VARCHAR(20),
  dept_no          INT            NOT NULL,
PRIMARY KEY (project_no),
UNIQUE      (project_name),
FOREIGN KEY (dept_no) REFERENCES department(dept_no) );

CREATE TABLE assignment
( emp_id           VARCHAR(8)     NOT NULL,
  project_no       INT            NOT NULL,
  hours            DECIMAL(3,1)   NOT NULL,
PRIMARY KEY (emp_id, project_no),
FOREIGN KEY (emp_id) REFERENCES employee(emp_id),
FOREIGN KEY (project_no) REFERENCES project(project_no) );

CREATE TABLE dependent
( emp_id           VARCHAR(8)     NOT NULL,
  dependent_name   VARCHAR(20)    NOT NULL,
  gender           CHAR(1),
  birth_date       DATE,
  relationship     VARCHAR(10),
PRIMARY KEY (emp_id, dependent_name),
FOREIGN KEY (emp_id) REFERENCES employee(emp_id) );

INSERT INTO employee
VALUES  ('E1001','Arjun','R','Mehta','1966-02-14','Kothrud, Pune','M',72000,'E1002',5),
        ('E1002','Kavita','S','Rao','1964-11-03','Baner, Pune','F',95000,'E1008',5),
        ('E1003','Rohan','P','Desai','1969-05-21','Nashik Road, Nashik','M',54000,'E1004',4),
        ('E1004','Sunita','M','Kulkarni','1958-08-30','Aundh, Pune','F',102000,'E1008',4),
        ('E1005','Vikram','A','Joshi','1963-01-17','Hadapsar, Pune','M',86000,'E1002',5),
        ('E1006','Priya','N','Shah','1974-09-09','Wakad, Pune','F',54000,'E1002',5),
        ('E1007','Imran','K','Sheikh','1970-04-12','Panchavati, Nashik','M',54000,'E1004',4),
        ('E1008','Ramesh','V','Iyer','1949-12-01','Koregaon Park, Pune','M',150000,null,1);

INSERT INTO department
VALUES  ('Engineering',5,'E1002','2010-04-01'),
        ('Operations',4,'E1004','2012-07-15'),
        ('Corporate',1,'E1008','2005-01-10');

INSERT INTO project
VALUES  ('Atlas',1,'Chakan',5),
        ('Beacon',2,'Talegaon',5),
        ('Cobalt',3,'Pune',5),
        ('Digitisation',10,'Nashik',4),
        ('Restructure',20,'Pune',1),
        ('Wellness',30,'Nashik',4);

INSERT INTO assignment
VALUES  ('E1001',1,32.5),
        ('E1001',2,7.5),
        ('E1005',3,40.0),
        ('E1006',1,20.0),
        ('E1006',2,20.0),
        ('E1002',2,10.0),
        ('E1002',3,10.0),
        ('E1002',10,10.0),
        ('E1002',20,10.0),
        ('E1003',30,30.0),
        ('E1003',10,10.0),
        ('E1007',10,35.0),
        ('E1007',30,5.0),
        ('E1004',30,20.0),
        ('E1004',20,15.0),
        ('E1008',20,16.0);

INSERT INTO dependent
VALUES  ('E1002','Ananya','F','1990-03-12','Daughter'),
        ('E1002','Kabir','M','1987-08-19','Son'),
        ('E1002','Neha','F','1965-06-02','Spouse'),
        ('E1004','Suresh','M','1956-02-11','Spouse'),
        ('E1001','Aarav','M','1995-01-04','Son'),
        ('E1001','Diya','F','1997-11-22','Daughter'),
        ('E1001','Pooja','F','1968-05-05','Spouse');

INSERT INTO dept_location
VALUES  (1,'Pune'),
        (4,'Nashik'),
        (5,'Chakan'),
        (5,'Talegaon'),
        (5,'Pune');

ALTER TABLE employee
 ADD CONSTRAINT emp_manager FOREIGN KEY (manager_id) REFERENCES employee(emp_id);
ALTER TABLE employee
 ADD CONSTRAINT emp_dept FOREIGN KEY (dept_no) REFERENCES department(dept_no);
